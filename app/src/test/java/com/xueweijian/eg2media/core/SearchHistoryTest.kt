package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun `record puts newest first and dedupes`() {
        val h1 = SearchHistory.record(emptyList(), "湖边")
        assertEquals(listOf("湖边"), h1)

        val h2 = SearchHistory.record(h1, "猫")
        assertEquals(listOf("猫", "湖边"), h2)

        val h3 = SearchHistory.record(h2, "湖边")
        assertEquals(listOf("湖边", "猫"), h3)
    }

    @Test
    fun `record caps at max and drops oldest`() {
        var h = emptyList<String>()
        for (q in listOf("1", "2", "3", "4", "5", "6")) h = SearchHistory.record(h, q)
        assertEquals(listOf("6", "5", "4", "3", "2"), h)
    }

    @Test
    fun `blank query is not recorded`() {
        val h = listOf("猫")
        assertEquals(h, SearchHistory.record(h, "   "))
        assertEquals(h, SearchHistory.record(h, ""))
    }

    @Test
    fun `remove drops matching entry`() {
        val h = listOf("猫", "湖边")
        assertEquals(listOf("湖边"), SearchHistory.remove(h, "猫"))
    }
}
