package com.xueweijian.eg2media.embed

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.xueweijian.eg2media.core.DeviceRouter
import com.xueweijian.eg2media.core.ModelRoute
import com.xueweijian.eg2media.core.Prompts
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * EG2 推理管理器（HANDOFF §2 两模型制：740M 常驻管检索）。
 * - 模型文件路径 = app 私有目录/models/{DeviceRouter 路由的文件名}
 * - GPU delegate 初始化失败自动降 CPU（HANDOFF §0 GPU→CPU fallback 必做）
 * - 输出 768d 已 L2 归一化向量；入库前由调用方做 Mrl 截断
 * - 全接口持同一把锁：worker / ViewModel / MediaObserver 三方并发使用+释放，
 *   唯一 embedder 实例的生命周期必须串行化（释放后重建由 ensure 兜底）
 */
object EmbedderManager {

    private val lock = ReentrantLock()

    @Volatile
    private var embedder: UniversalEmbedder? = null

    private var activeDelegate: Delegate = Delegate.GPU

    fun route(context: Context): ModelRoute {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Build.SOC_MODEL 是 API 31+ 字段（e2e 在 API 30 模拟器抓到 NoSuchFieldError）；
        // 低版本回退 Build.HARDWARE（SoC 型号串如 "qcom"，NPU 表匹配不上自然走通用版）
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        return DeviceRouter.route(
            socModel = socModel,
            lowRam = am.isLowRamDevice,
            memoryClassMb = am.memoryClass,
        )
    }

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), route(context).eg2File)

    fun isModelReady(context: Context): Boolean = modelFile(context).length() > 0

    fun ensure(context: Context): UniversalEmbedder = lock.withLock {
        embedder?.let { return it }
        val file = modelFile(context)
        check(file.exists() && file.length() > 0) {
            "EG2 model missing at $file — push or download ${route(context).eg2File} first"
        }
        // 全 CPU 策略（v0.16，真机诊断 norm=0 案）：
        // - Adreno GPU delegate 初始化"成功"但静默输出全零向量（norm=0.0000，dims=768 正常）
        //   ——比崩溃更阴险，且 GPU 对文本塔毫无加速（模型卡：CPU 27.1ms vs GPU 25.9ms）
        // - vision 塔已有独立 bug 强制 CPU（v0.15，static dimensions）
        // - 模拟器 e2e 6/6 全过 = 全 CPU 路径已验证；模型卡 CPU benchmark 即官方主路径
        // GPU 代码保留在 git 历史（v0.15 及之前），待 litertlm GPU 后端成熟再评估
        val created = create(context, file.absolutePath, Delegate.CPU).also {
            activeDelegate = Delegate.CPU
        }
        embedder = created
        return created
    }

    private fun create(context: Context, path: String, delegate: Delegate): UniversalEmbedder {
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(path)
                    .setDelegate(delegate)
                    .build()
            )
            .setTextDelegate(delegate)
            // vision 塔强制 CPU：litertlm 0.18.0 的 GPU vision executor 有静态维度 bug
            // （真机 GPU 报 TensorBuffer must have all static dimensions，模拟器 CPU 全过；
            // 模型卡实测 CPU 175ms/张 vs GPU 119ms，代价可接受）。文本塔保留 GPU 加速。
            .setVisionDelegate(Delegate.CPU)
            .setAudioDelegate(Delegate.CPU)
            .setL2Normalize(true)
            // 740M 视觉塔只接受 70/140 个 image soft tokens（模型卡规格）；
            // 不设则 patch 数随图片尺寸浮动，输出张量出现动态维度，
            // litert 报 "TensorBuffer must have all static dimensions"。
            // 70 = 低延迟签名（官方 benchmark 所用）；140 = 更细粒度可后续切换。
            .setVisionTokensPerImage(VISION_TOKENS_PER_IMAGE)
            .build()
        return UniversalEmbedder.createFromOptions(context, options)
    }

    fun activeDelegateName(): String = "CPU+visionCPU"

    /** 零向量哨兵：GPU 案例证明 delegate 可能静默输出全零（dims 正常但 norm=0），
     *  必须在出口拦截，否则零向量入库污染整个检索库 */
    private fun validated(v: FloatArray, what: String): FloatArray {
        val norm = com.xueweijian.eg2media.core.DiagSpec.l2norm(v)
        require(norm > 0.1) {
            "embedding near-zero (norm=%.6f, dims=%d) — delegate/model output invalid [%s]".format(
                norm, v.size, what,
            )
        }
        return v
    }

    /** 检索查询编码（自动加 SearchQuery 前缀，HANDOFF §2） */
    fun embedQuery(context: Context, query: String): FloatArray = lock.withLock {
        validated(
            ensure(context).embedText(Prompts.searchQuery(query))
                .embeddings()[0].floatEmbedding(),
            "embedQuery",
        )
    }

    /** 图片编码（视觉塔） */
    fun embedImage(context: Context, bitmap: Bitmap): FloatArray = lock.withLock {
        validated(
            ensure(context)
                .embedImage(BitmapImageBuilder(bitmap).build())
                .embeddings()[0].floatEmbedding(),
            "embedImage",
        )
    }

    /** 原始文本编码（入库文档用，前缀由调用方控制） */
    fun embedText(context: Context, text: String): FloatArray = lock.withLock {
        validated(
            ensure(context).embedText(text).embeddings()[0].floatEmbedding(),
            "embedText",
        )
    }

    fun release() = lock.withLock {
        embedder?.close()
        embedder = null
    }

    /** 740M 视觉塔合法值 70/140（模型卡）；70 = 低延迟签名 */
    const val VISION_TOKENS_PER_IMAGE = 70
}
