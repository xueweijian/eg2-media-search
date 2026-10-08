package com.xueweijian.eg2media.core

import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 真机自检规格（方案 ①：golden vectors + 行为断言）。
 *
 * 三类断言（对应三条风险面）：
 * 1. 形状断言：输出 768d 且 L2 归一化（保命约束 #1/#2）
 * 2. golden 断言：同输入跨运行余弦 ≥ 0.99（模型版本/delegate/预处理变更的回归网）
 * 3. 判别断言：中英同义对应高相似、跨语义域低相似（语义空间没塌缩）
 *
 * golden 存储：files/diagnostics/golden.txt，行格式 `key;dims;base64f32le`
 */
object DiagSpec {

    // ── 标准输入集（key 稳定，golden 依赖）──────────────────────────
    val TEXTS = listOf(
        "一只黄色的猫坐在窗台上晒太阳",
        "量子计算机使用超导量子比特进行运算",
        "山地自行车越野赛道的下坡技巧",
    )
    const val TEXT_PREFIX = "t"

    /** 跨语言同义对（判别断言：应高相似） */
    const val PAIR_FIRST = "一只黄色的猫坐在窗台上晒太阳"
    const val PAIR_SECOND = "a yellow cat sitting on a windowsill in the sunshine"

    /** 跨语义域对（判别断言：应低相似） */
    const val UNRELATED_TEXT = "量子计算机使用超导量子比特进行运算"

    // ── 判定规则 ────────────────────────────────────────────────────
    const val EXPECTED_DIMS = 768
    const val NORM_TOLERANCE = 1e-3
    const val GOLDEN_COS_THRESHOLD = 0.99
    const val PAIR_COS_THRESHOLD = 0.5
    const val DISCRIMINATION_MARGIN = 0.1

    fun judgeDimension(dims: Int): Boolean = dims == EXPECTED_DIMS

    fun judgeNorm(norm: Double): Boolean = abs(norm - 1.0) < NORM_TOLERANCE

    fun judgeGolden(cos: Double): Boolean = cos >= GOLDEN_COS_THRESHOLD

    fun judgeDiscrimination(pairCos: Double, unrelatedCos: Double): Boolean =
        pairCos >= PAIR_COS_THRESHOLD && (pairCos - unrelatedCos) >= DISCRIMINATION_MARGIN

    // ── 向量工具 ────────────────────────────────────────────────────
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun l2norm(a: FloatArray): Double = sqrt(a.fold(0.0) { acc, v -> acc + v.toDouble() * v.toDouble() })

    // ── golden 行格式（key;dims;b64）───────────────────────────────
    fun encodeGolden(entries: Map<String, FloatArray>): String =
        entries.entries.joinToString("\n") { (k, v) -> "$k;${v.size};${floatsToB64(v)}" }

    fun decodeGolden(text: String): Map<String, FloatArray> {
        val out = mutableMapOf<String, FloatArray>()
        text.lineSequence().forEach { line ->
            val parts = line.split(';')
            if (parts.size != 3) return@forEach
            val dims = parts[1].toIntOrNull() ?: return@forEach
            val vec = runCatching { b64ToFloats(parts[2]) }.getOrNull() ?: return@forEach
            if (vec.size == dims) out[parts[0]] = vec
        }
        return out
    }

    fun floatsToB64(v: FloatArray): String {
        val bytes = ByteArray(v.size * 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(v)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun b64ToFloats(s: String): FloatArray {
        val bytes = Base64.getDecoder().decode(s)
        require(bytes.size % 4 == 0) { "bad b64 float payload" }
        val out = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
