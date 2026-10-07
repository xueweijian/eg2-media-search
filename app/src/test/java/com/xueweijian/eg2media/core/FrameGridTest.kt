package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频抽帧网格（HANDOFF §4：视频自建抽帧）。
 * 区间语义 [t, t+step)，末帧收在 duration；超长视频自动抽稀到 maxFrames 内。
 */
class FrameGridTest {

    @Test
    fun `短于一步的视频单帧`() {
        val f = FrameGrid.frames(durationMs = 3_000, stepMs = 4_000)
        assertEquals(1, f.size)
        assertEquals(0L, f[0].startMs)
        assertEquals(3_000L, f[0].endMs)
    }

    @Test
    fun `整除时序号与区间正确`() {
        val f = FrameGrid.frames(durationMs = 16_000, stepMs = 4_000)
        assertEquals(4, f.size)
        assertEquals(listOf(0L, 4_000L, 8_000L, 12_000L), f.map { it.startMs })
        assertEquals(16_000L, f.last().endMs)
        // 全覆盖无缝
        for (i in 1 until f.size) assertEquals(f[i - 1].endMs, f[i].startMs)
    }

    @Test
    fun `不整除时末帧收尾到 duration`() {
        val f = FrameGrid.frames(durationMs = 17_000, stepMs = 4_000)
        assertEquals(5, f.size) // 0,4,8,12,16
        assertEquals(17_000L, f.last().endMs)
    }

    @Test
    fun `零与负时长为空`() {
        assertEquals(0, FrameGrid.frames(0, 4_000).size)
        assertEquals(0, FrameGrid.frames(-1, 4_000).size)
    }

    @Test
    fun `超长视频抽稀到上限内`() {
        // 2 小时视频 @4s = 1800 帧 > 240 上限 → 自动放大步长
        val f = FrameGrid.frames(durationMs = 7_200_000, stepMs = 4_000, maxFrames = 240)
        assertTrue(f.size in 1..240)
        // 步长被放大（相邻帧间隔 > 4s）
        assertTrue(f.size < 300)
        assertEquals(7_200_000L, f.last().endMs)
        // 覆盖起止
        assertEquals(0L, f.first().startMs)
    }

    @Test
    fun `序号连续`() {
        val f = FrameGrid.frames(60_000, 4_000)
        assertEquals((0 until f.size).toList(), f.map { it.index })
    }
}
