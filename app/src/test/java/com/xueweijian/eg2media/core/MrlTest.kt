package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * HANDOFF §7 保命约束 #2：MRL 截断后必须重新 L2 归一化；
 * query 与库维度必须一致。纯数学，必须 100% 正确。
 */
class MrlTest {

    private fun norm(v: FloatArray): Double = sqrt(v.sumOf { it.toDouble() * it })

    @Test
    fun `截断到 512 后范数为 1`() {
        val v = FloatArray(768) { (it % 13 + 1).toFloat() }
        val out = Mrl.truncateAndRenormalize(v, 512)
        assertEquals(512, out.size)
        assertEquals(1.0, norm(out), 1e-5)
    }

    @Test
    fun `截断保留前缀原值方向`() {
        val v = FloatArray(768) { (it % 7 + 1).toFloat() }
        val out = Mrl.truncateAndRenormalize(v, 256)
        // 方向一致：夹角余弦 ≈ 1
        val prefix = v.copyOfRange(0, 256)
        var dot = 0.0
        for (i in 0 until 256) dot += prefix[i].toDouble() * out[i]
        val cos = dot / (norm(prefix) * norm(out))
        assertTrue(abs(cos - 1.0) < 1e-4)
    }

    @Test
    fun `维度 768 恒等（已归一化输入则不变）`() {
        val v = FloatArray(768) { 1f / sqrt(768.0).toFloat() }
        val out = Mrl.truncateAndRenormalize(v, 768)
        for (i in v.indices) assertTrue(abs(v[i] - out[i]) < 1e-6)
    }

    @Test
    fun `非法维度拒绝`() {
        val v = FloatArray(768)
        listOf(0, -1, 769).forEach { d ->
            try {
                Mrl.truncateAndRenormalize(v, d)
                throw AssertionError("dims=$d should be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `点积即余弦（归一化不变式）`() {
        val a = Mrl.truncateAndRenormalize(FloatArray(768) { (it % 11 + 1).toFloat() }, 512)
        val b = Mrl.truncateAndRenormalize(FloatArray(768) { (it % 5 + 2).toFloat() }, 512)
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        assertTrue(dot in -1.0001..1.0001)
    }
}
