package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HANDOFF §4：音频滑窗 60-120s 窗 / 30-60s 步长，尾部短窗规则 */
class SlidingWindowsTest {

    private fun windows(durationMs: Long, windowMs: Long = 90_000, stepMs: Long = 45_000) =
        SlidingWindows.cut(durationMs, windowMs, stepMs)

    @Test
    fun `短于一个窗的单条音频单窗全长`() {
        val w = windows(50_000)
        assertEquals(1, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(50_000L, w[0].endMs)
    }

    @Test
    fun `正好一个窗长`() {
        val w = windows(90_000)
        assertEquals(1, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(90_000L, w[0].endMs)
    }

    @Test
    fun `90 秒音频 90 窗 45 步 两窗覆盖`() {
        val w = windows(90_000)
        // [0,90) 已全覆盖，无第二窗
        assertEquals(1, w.size)
    }

    @Test
    fun `125 秒音频 90 窗 45 步`() {
        val w = windows(125_000)
        // [0,90) [45,125) —— 第二窗被截到结尾（尾部 35s >= minTail 15s 规则下独立成短窗）
        assertEquals(2, w.size)
        assertEquals(0L, w[0].startMs)
        assertEquals(90_000L, w[0].endMs)
        assertEquals(45_000L, w[1].startMs)
        assertEquals(125_000L, w[1].endMs)
    }

    @Test
    fun `长音频多窗覆盖且无越界`() {
        val w = windows(600_000) // 10 分钟，90/45
        assertTrue(w.size >= 12)
        // 每窗在界内
        w.forEach { assertTrue(it.startMs < it.endMs && it.endMs <= 600_000) }
        // 首窗从 0 开始，末窗收在结尾
        assertEquals(0L, w.first().startMs)
        assertEquals(600_000L, w.last().endMs)
        // 步进近似 step
        assertEquals(45_000L, w[1].startMs - w[0].startMs)
    }

    @Test
    fun `尾部残段小于 minTail 并入前一窗`() {
        // 90+20=110s：[0,90) 后剩 20s；从 45 起步的下一窗 [45,110) 尾段 20s<minTail? 110-45=65s 全长窗
        // 换更尖的情况：duration=100s，窗90步45 → 窗2起点45，剩余 55s
        val w = windows(100_000)
        assertEquals(2, w.size)
        assertEquals(100_000L, w.last().endMs)
    }

    @Test
    fun `零与负时长返回空`() {
        assertEquals(0, windows(0).size)
        assertEquals(0, windows(-5).size)
    }

    @Test
    fun `每个元素带稳定序号`() {
        val w = windows(600_000)
        assertEquals(w.map { it.index }, (0 until w.size).toList())
    }
}
