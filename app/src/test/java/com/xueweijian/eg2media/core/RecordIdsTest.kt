package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HANDOFF §4：SemanticRetriever 的 insertImage/insertAudio 没有时间戳字段，
 * 时间戳区间必须编码进记录 ID 自管。encode/decode 必须无损往返。
 */
class RecordIdsTest {

    @Test
    fun `往返无损`() {
        val ref = RecordRef(sourceId = "vid42", modality = Modality.AUDIO, startMs = 120_000, endMs = 180_000)
        val id = RecordIds.encode(ref)
        assertEquals(ref, RecordIds.decode(id))
    }

    @Test
    fun `图片整档记录用零区间`() {
        val ref = RecordRef(sourceId = "img7", modality = Modality.IMAGE, startMs = 0, endMs = 0)
        assertEquals(ref, RecordIds.decode(RecordIds.encode(ref)))
    }

    @Test
    fun `ID 格式可读稳定`() {
        val id = RecordIds.encode(RecordRef("s1", Modality.VIDEO_FRAME, 5_000, 10_000))
        assertEquals("s1|vf|5000-10000", id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法 ID 抛异常`() {
        RecordIds.decode("garbage")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sourceId 不允许竖线字符`() {
        RecordIds.encode(RecordRef("a|b", Modality.IMAGE, 0, 0))
    }
}
