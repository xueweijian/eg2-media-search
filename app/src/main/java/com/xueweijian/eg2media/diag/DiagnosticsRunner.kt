package com.xueweijian.eg2media.diag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import com.xueweijian.eg2media.core.DiagSpec
import com.xueweijian.eg2media.embed.EmbedderManager
import java.io.File
import java.security.MessageDigest

/**
 * 真机自检执行器（方案 ①）：
 * 设备/模型信息 → embedder 加载 → 标准输入集（文本 + 程序化图像）
 * → 形状断言 + golden 断言 + 语义判别断言 → 报告。
 *
 * 标准图像程序化生成（确定性，无资产依赖）；音频位留待音频模态上线。
 */
class DiagnosticsRunner(private val context: Context) {

    data class ItemResult(
        val key: String,
        val kind: String, // text | image
        val dims: Int,
        val norm: Double,
        val goldenCos: Double?, // null = 本次为基线采集
        val coldMs: Long,
        val warmMs: Long,
        val pass: Boolean,
    )

    data class Report(
        val deviceModel: String,
        val socModel: String,
        val androidVersion: String,
        val abi: String,
        val lowRam: Boolean,
        val modelFileName: String,
        val modelSizeBytes: Long,
        val modelSha256Prefix: String?,
        val delegate: String,
        val loadMs: Long,
        val items: List<ItemResult>,
        val pairCos: Double,
        val unrelatedCos: Double,
        val discriminationPass: Boolean,
        val goldenBaseline: Boolean,
        val overallPass: Boolean,
        val failures: List<String>,
        val recentCrash: String? = null,
    ) {
        fun toJson(): String = buildString {
            append("{\n")
            append("  \"device\": \"$deviceModel\",\n")
            append("  \"soc\": \"$socModel\",\n")
            append("  \"android\": \"$androidVersion\",\n")
            append("  \"abi\": \"$abi\",\n")
            append("  \"low_ram\": $lowRam,\n")
            append("  \"model\": {\n")
            append("    \"file\": \"$modelFileName\",\n")
            append("    \"bytes\": $modelSizeBytes,\n")
            append("    \"sha256_prefix\": ${modelSha256Prefix?.let { "\"$it\"" } ?: "null"},\n")
            append("    \"delegate\": \"$delegate\",\n")
            append("    \"load_ms\": $loadMs\n")
            append("  },\n")
            append("  \"items\": [\n")
            items.forEachIndexed { i, r ->
                append("    {\"key\": \"${r.key}\", \"kind\": \"${r.kind}\", \"dims\": ${r.dims}, ")
                append("\"norm\": ${"%.6f".format(r.norm)}, \"golden_cos\": ${r.goldenCos?.let { "%.6f".format(it) } ?: "null"}, ")
                append("\"cold_ms\": ${r.coldMs}, \"warm_ms\": ${r.warmMs}, \"pass\": ${r.pass}}")
                append(if (i == items.lastIndex) "\n" else ",\n")
            }
            append("  ],\n")
            append("  \"discrimination\": {\"pair_cos\": ${"%.6f".format(pairCos)}, ")
            append("\"unrelated_cos\": ${"%.6f".format(unrelatedCos)}, \"pass\": $discriminationPass},\n")
            append("  \"golden_baseline\": $goldenBaseline,\n")
            append("  \"overall_pass\": $overallPass,\n")
            append("  \"failures\": [")
            failures.forEachIndexed { i, f ->
                append(if (i == 0) "" else ", ")
                append("\"${f.replace("\"", "'")}\"")
            }
            append("]")
            if (recentCrash != null) {
                append(",\n  \"recent_crash\": \"")
                append(
                    recentCrash
                        .replace("\\", "\\\\")
                        .replace("\"", "'")
                        .replace("\n", "\\n")
                        .replace("\t", " "),
                )
                append("\"")
            }
            append("\n}\n")
        }
    }

    fun goldenFile(): File = File(File(context.filesDir, "diagnostics"), "golden.txt")

    /** @param resetGolden true = 删除既有 golden，本次重新采集基线 */
    fun run(onStep: (String) -> Unit, resetGolden: Boolean = false): Report {
        val failures = mutableListOf<String>()

        // 1. embedder 加载（delegate + 耗时）
        onStep("加载模型…")
        val t0 = System.nanoTime()
        EmbedderManager.ensure(context)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val delegate = EmbedderManager.activeDelegateName()

        // 2. golden 基线（reset 或首次 = 采集模式）
        val gfile = goldenFile()
        if (resetGolden && gfile.exists()) gfile.delete()
        val golden: Map<String, FloatArray> =
            if (gfile.exists()) DiagSpec.decodeGolden(gfile.readText()) else emptyMap()
        val isBaseline = golden.isEmpty()

        // 3. 标准输入集（首个 embed 含冷启动耗时）
        val results = mutableListOf<ItemResult>()
        val vectors = mutableMapOf<String, FloatArray>()
        var first = true

        fun embed(key: String, kind: String, block: () -> FloatArray) {
            val t = System.nanoTime()
            val v = block()
            val ms = (System.nanoTime() - t) / 1_000_000
            vectors[key] = v
            val gv = golden[key]
            val cos = if (gv != null && gv.size == v.size) DiagSpec.cosine(gv, v) else null
            val norm = DiagSpec.l2norm(v)
            if (!DiagSpec.judgeDimension(v.size)) failures += "$key: 维度 ${v.size} ≠ ${DiagSpec.EXPECTED_DIMS}"
            if (!DiagSpec.judgeNorm(norm)) failures += "$key: 范数 ${"%.6f".format(norm)} ≠ 1"
            if (cos != null && !DiagSpec.judgeGolden(cos)) failures += "$key: golden 余弦 ${"%.4f".format(cos)} < ${DiagSpec.GOLDEN_COS_THRESHOLD}"
            results.add(
                ItemResult(
                    key = key, kind = kind, dims = v.size, norm = norm, goldenCos = cos,
                    coldMs = if (first) ms else 0, warmMs = if (first) 0 else ms,
                    pass = DiagSpec.judgeDimension(v.size) && DiagSpec.judgeNorm(norm) &&
                        (cos == null || DiagSpec.judgeGolden(cos)),
                )
            )
            first = false
        }

        DiagSpec.TEXTS.forEachIndexed { i, text ->
            onStep("编码文本 ${i + 1}/${DiagSpec.TEXTS.size}…")
            embed("t$i", "text") { EmbedderManager.embedText(context, text) }
        }
        listOf("img_a" to ::standardBitmapA, "img_b" to ::standardBitmapB).forEach { (key, gen) ->
            onStep("编码图像 $key…")
            val bmp = gen()
            try {
                embed(key, "image") { EmbedderManager.embedImage(context, bmp) }
            } finally {
                bmp.recycle()
            }
        }

        // 4. 语义判别断言（中英同义 vs 跨语义域）
        onStep("语义判别对…")
        val pairCos = DiagSpec.cosine(
            EmbedderManager.embedText(context, DiagSpec.PAIR_FIRST),
            EmbedderManager.embedText(context, DiagSpec.PAIR_SECOND),
        )
        val unrelatedCos = DiagSpec.cosine(
            EmbedderManager.embedText(context, DiagSpec.PAIR_FIRST),
            EmbedderManager.embedText(context, DiagSpec.UNRELATED_TEXT),
        )
        val discriminationPass = DiagSpec.judgeDiscrimination(pairCos, unrelatedCos)
        if (!discriminationPass) {
            failures += "语义判别不足：pair=${"%.4f".format(pairCos)} unrelated=${"%.4f".format(unrelatedCos)}"
        }

        // 5. golden 写入（基线采集 / 合并保留）
        val merged = if (isBaseline) vectors else golden + vectors
        gfile.parentFile?.mkdirs()
        gfile.writeText(DiagSpec.encodeGolden(merged))
        if (isBaseline) onStep("golden 基线已采集")

        val model = EmbedderManager.modelFile(context)
        val crash = recentCrash(context)
        return Report(
            deviceModel = Build.MODEL ?: "?",
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE ?: "?",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            lowRam = runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
            }.getOrDefault(false),
            modelFileName = model.name,
            modelSizeBytes = model.length(),
            modelSha256Prefix = sha256Prefix(model),
            delegate = delegate,
            loadMs = loadMs,
            items = results,
            pairCos = pairCos,
            unrelatedCos = unrelatedCos,
            discriminationPass = discriminationPass,
            goldenBaseline = isBaseline,
            overallPass = failures.isEmpty(),
            failures = failures,
            recentCrash = crash,
        )
    }

    /** 闪退日志尾部（无则 null）——随诊断 JSON 导出，远程定位闪退 */
    private fun recentCrash(context: Context, maxChars: Int = 900): String? {
        val f = File(context.filesDir, "crash-log.txt")
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return text.takeLast(maxChars)
    }

    private fun sha256Prefix(f: File, len: Int = 16): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(len)
    }.getOrNull()

    companion object {
        // ── 程序化标准图（确定性：同参数同像素，无资产依赖）─────────
        const val IMG_SIZE = 256

        fun standardBitmapA(): Bitmap {
            val bmp = Bitmap.createBitmap(IMG_SIZE, IMG_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint()
            for (x in 0 until IMG_SIZE) {
                val r = x * 255 / IMG_SIZE
                paint.color = Color.rgb(0, 100 + r / 3, 255 - r)
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), IMG_SIZE.toFloat(), paint)
            }
            paint.color = Color.RED
            canvas.drawCircle(IMG_SIZE / 2f, IMG_SIZE / 2f, IMG_SIZE / 5f, paint)
            return bmp
        }

        fun standardBitmapB(): Bitmap {
            val bmp = Bitmap.createBitmap(IMG_SIZE, IMG_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint()
            for (x in 0 until IMG_SIZE step 32) {
                paint.color = Color.BLACK
                canvas.drawRect(x.toFloat(), 0f, (x + 16).toFloat(), IMG_SIZE.toFloat(), paint)
            }
            paint.color = Color.YELLOW
            val path = android.graphics.Path()
            path.moveTo(IMG_SIZE * 0.6f, IMG_SIZE * 0.85f)
            path.lineTo(IMG_SIZE * 0.8f, IMG_SIZE * 0.85f)
            path.lineTo(IMG_SIZE * 0.7f, IMG_SIZE * 0.5f)
            path.close()
            canvas.drawPath(path, paint)
            return bmp
        }
    }
}
