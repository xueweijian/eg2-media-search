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
}
