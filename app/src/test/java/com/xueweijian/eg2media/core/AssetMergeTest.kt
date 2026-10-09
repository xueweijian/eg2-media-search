package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetMergeTest {

    @Test
    fun `media key is plain id and custom key is u-prefixed`() {
        assertEquals("123", AssetMerge.mediaKey(123L))
        assertEquals("ucontent://media/pick/x", AssetMerge.customKey("content://media/pick/x"))
    }

    @Test
    fun `merge dedupes custom against visible by key`() {
        data class Img(val key: String)

        val visible = listOf(Img("1"), Img("2"))
        val custom = listOf(Img("2"), Img("ucontent://pick/9"))
        val merged = AssetMerge.merge(visible, custom, { it.key })

        assertEquals(listOf(Img("1"), Img("2"), Img("ucontent://pick/9")), merged)
    }

    @Test
    fun `merge with empty custom returns visible as-is`() {
        val visible = listOf("a", "b")
        assertEquals(visible, AssetMerge.merge(visible, emptyList<String>(), { it }))
    }

    @Test
    fun `indexed asset keys strips recordId suffix`() {
        val ids = setOf(
            "123|img|0-0",
            "456|vf|0-45000",
            "ucontent://pick/9|img|0-0",
        )
        val keys = AssetMerge.indexedAssetKeys(ids)
        assertEquals(setOf("123", "456", "ucontent://pick/9"), keys)
        assertTrue("456|vf" !in keys)
    }

    // ---- v0.24.1（Round 26.5）：MediaStore 形式 custom uri 归一 ----

    @Test
    fun `media store form uris resolve to numeric id`() {
        assertEquals(17L, AssetMerge.mediaStoreUriId("content://media/external/images/media/17"))
        assertEquals(1000079580L, AssetMerge.mediaStoreUriId("content://media/external/video/media/1000079580"))
        assertEquals(null, AssetMerge.mediaStoreUriId("content://media/picker/0/media/17"))
        assertEquals(null, AssetMerge.mediaStoreUriId("content://media/external/images/media/abc"))
        assertEquals(null, AssetMerge.mediaStoreUriId("file:///sdcard/x.jpg"))
    }

    @Test
    fun `normalized custom key joins visible namespace for media store form`() {
        // MediaStore 形式 → 数字 id（与可见集同命名空间，merge 天然去重）
        assertEquals("17", AssetMerge.normalizedCustomKey("content://media/external/images/media/17"))
        // picker 形式 → "u"+uri
        assertEquals(
            "ucontent://media/picker/0/media/9",
            AssetMerge.normalizedCustomKey("content://media/picker/0/media/9"),
        )
    }

    @Test
    fun `merge dedupes media store form custom against visible via normalized key`() {
        data class Img(val key: String)

        val visible = listOf(Img("17"))
        val custom = listOf(
            Img("17"), // picker 返回的 MediaStore 形式，归一后与可见集同 key → 跳过
            Img("ucontent://pick/9"),
        )
        val merged = AssetMerge.merge(visible, custom, { it.key })
        assertEquals(2, merged.size)
        assertEquals(Img("17"), merged[0])
        assertEquals(Img("ucontent://pick/9"), merged[1])
    }
}
