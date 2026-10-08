package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断判定与 golden vector 存取契约（真机自检方案 ①，HANDOFF 风险矩阵）。
 * golden 用自定义行格式（key;dims;b64）——零 JSON 依赖，JVM 可测。
 */
class DiagSpecTest {

    @Test
    fun `余弦基本性质`() {
        val v = floatArrayOf(1f, 0f, 0f)
        assertEquals(1.0, DiagSpec.cosine(v, v), 1e-9)
        assertEquals(0.0, DiagSpec.cosine(v, floatArrayOf(0f, 1f, 0f)), 1e-9)
        assertEquals(-1.0, DiagSpec.cosine(v, floatArrayOf(-1f, 0f, 0f)), 1e-9)
    }

    @Test
    fun `b64 roundtrip 无损`() {
        val v = floatArrayOf(0.1f, -0.5f, 3e-7f, 0f, 1f)
        val back = DiagSpec.b64ToFloats(DiagSpec.floatsToB64(v))
        assertTrue(v.contentEquals(back))
    }

    @Test
    fun `golden 编解码 roundtrip 多条目`() {
        val g = mapOf(
            "t0" to floatArrayOf(0.1f, 0.2f),
            "t1" to floatArrayOf(-1f, 0.5f),
            "img_a" to floatArrayOf(0f, 0f, 0f, 1f),
        )
        val decoded = DiagSpec.decodeGolden(DiagSpec.encodeGolden(g))
        assertEquals(g.keys, decoded.keys)
        g.forEach { (k, v) -> assertTrue(v.contentEquals(decoded[k])) }
    }

    @Test
    fun `损坏行被跳过不炸`() {
        val good = DiagSpec.encodeGolden(mapOf("t0" to floatArrayOf(1f)))
        val bad = "not;a;valid;format\n\nzzz;;\n" + good
        val decoded = DiagSpec.decodeGolden(bad)
        assertTrue(decoded.containsKey("t0"))
    }

    @Test
    fun `维度与范数判定`() {
        assertTrue(DiagSpec.judgeDimension(768))
        assertFalse(DiagSpec.judgeDimension(512))
        assertTrue(DiagSpec.judgeNorm(1.0000001))
        assertFalse(DiagSpec.judgeNorm(1.01))
        assertFalse(DiagSpec.judgeNorm(0.5))
    }

    @Test
    fun `golden 相似阈值`() {
        assertTrue(DiagSpec.judgeGolden(0.999))
        assertTrue(DiagSpec.judgeGolden(0.99))
        assertFalse(DiagSpec.judgeGolden(0.98))
    }

    @Test
    fun `语义判别判定`() {
        // 相关对高 + 差距足够能量
        assertTrue(DiagSpec.judgeDiscrimination(pairCos = 0.8, unrelatedCos = 0.3))
        // pair 不够高
        assertFalse(DiagSpec.judgeDiscrimination(pairCos = 0.4, unrelatedCos = 0.1))
        // 差距不足（margin 不够）
        assertFalse(DiagSpec.judgeDiscrimination(pairCos = 0.6, unrelatedCos = 0.55))
        // 无关对竟更高
        assertFalse(DiagSpec.judgeDiscrimination(pairCos = 0.5, unrelatedCos = 0.7))
    }

    @Test
    fun `判别对定义完整可引用`() {
        // 标准输入集非空且 key 稳定（golden 依赖）
        assertTrue(DiagSpec.TEXTS.isNotEmpty())
        assertTrue(DiagSpec.PAIR_FIRST.isNotEmpty() && DiagSpec.PAIR_SECOND.isNotEmpty())
        assertTrue(DiagSpec.UNRELATED_TEXT.isNotEmpty())
        // 判别对语义上确实不同域
        assertTrue(DiagSpec.PAIR_FIRST != DiagSpec.UNRELATED_TEXT)
    }
}
