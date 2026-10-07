package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HANDOFF §4：检索 top-k 后合并相邻时间窗。
 * 保命约束 #3：只看排名——合并仅按几何相邻性，不做绝对分数门槛。
 */
class HitMergeTest {

    private fun hit(
        source: String,
        start: Long,
        end: Long,
        score: Double,
        modality: Modality = Modality.AUDIO,
    ) = Hit("id-$source-$start", source, modality, start, end, score)

    @Test
    fun `相邻同源窗合并成分数取 max 的区间`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.31),
                hit("a", 60_000, 120_000, 0.42), // 无缝相邻
            )
        )
        assertEquals(1, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(120_000L, merged[0].endMs)
        assertEquals(0.42, merged[0].score, 1e-9)
    }

    @Test
    fun `间隔超过 maxGap 不合并`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.4),
                hit("a", 300_000, 360_000, 0.5),
            ),
            maxGapMs = 5_000,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `间隔在 maxGap 内合并`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.4),
                hit("a", 63_000, 120_000, 0.5),
            ),
            maxGapMs = 5_000,
        )
        assertEquals(1, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(120_000L, merged[0].endMs)
    }

    @Test
    fun `不同来源不合并`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.4),
                hit("b", 0, 60_000, 0.4),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `不同模态不合并`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.4, Modality.AUDIO),
                hit("a", 0, 60_000, 0.4, Modality.VIDEO_FRAME),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `乱序输入先按时间轴稳定`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 60_000, 120_000, 0.5),
                hit("a", 0, 60_000, 0.4),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(120_000L, merged[0].endMs)
    }

    @Test
    fun `结果按分数降序`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.31),
                hit("b", 0, 60_000, 0.55),
                hit("a", 60_000, 120_000, 0.42),
            )
        )
        assertEquals(listOf(0.55, 0.42), merged.map { it.score })
        assertTrue(merged[0].score >= merged[1].score)
    }

    @Test
    fun `三窗链式合并成一个`() {
        val merged = HitMerge.merge(
            listOf(
                hit("a", 0, 60_000, 0.3),
                hit("a", 60_000, 120_000, 0.5),
                hit("a", 120_000, 180_000, 0.4),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(180_000L, merged[0].endMs)
    }

    @Test
    fun `空输入安全`() {
        assertEquals(0, HitMerge.merge(emptyList()).size)
    }
}
