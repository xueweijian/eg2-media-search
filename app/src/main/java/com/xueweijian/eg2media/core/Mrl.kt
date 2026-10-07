package com.xueweijian.eg2media.core

import kotlin.math.sqrt

/**
 * MRL 截断 + 重新 L2 归一化（HANDOFF §7 保命约束 #2）。
 * 多模态索引默认 512d；截断后不重归一化 = 点积不再是余弦，检索质量静默劣化。
 * 输入应已是完整 768d 向量（官方 task 输出已 L2 归一化，重归一化幂等安全）。
 */
object Mrl {

    fun truncateAndRenormalize(vec: FloatArray, dims: Int): FloatArray {
        require(dims in 1..vec.size) { "dims must be in 1..${vec.size}, got $dims" }
        val out = vec.copyOf(dims)
        var sumSq = 0.0
        for (v in out) sumSq += v.toDouble() * v
        if (sumSq <= 0.0) return out // 零向量无法归一化，原样返回（上游错误信号）
        val scale = 1.0 / sqrt(sumSq)
        for (i in out.indices) out[i] = (out[i] * scale).toFloat()
        return out
    }
}
