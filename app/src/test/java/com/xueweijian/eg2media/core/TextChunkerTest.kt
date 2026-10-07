package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档分块行为契约（HANDOFF §4：插入前自动分块的官方行为我们自管可测版本）。
 * 中文友好：按 。！？!?…\n 句子边界切，贪心组装，相邻块带重叠。
 */
class TextChunkerTest {

    @Test
    fun `空文本零块`() {
        assertEquals(0, TextChunker.chunk("").size)
    }

    @Test
    fun `短文本单块`() {
        val chunks = TextChunker.chunk("今天讲熵增原理。热力学第二定律。")
        assertEquals(1, chunks.size)
        assertEquals("今天讲熵增原理。热力学第二定律。", chunks[0].text)
        assertEquals(0, chunks[0].index)
    }

    @Test
    fun `长文本多块且序号连续`() {
        val text = buildString {
            repeat(60) { i -> append("这是第${i}句话，内容各不相同用于撑长度测试。") }
        }
        val chunks = TextChunker.chunk(text, targetChars = 300, overlapChars = 30)
        assertTrue("should be >1 chunk, got ${chunks.size}", chunks.size > 1)
        assertEquals(chunks.map { it.index }, (0 until chunks.size).toList())
    }

    @Test
    fun `相邻块有重叠`() {
        val text = buildString {
            repeat(80) { i -> append("句子编号${i}。") }
        }
        val chunks = TextChunker.chunk(text, targetChars = 200, overlapChars = 30)
        for (i in 1 until chunks.size) {
            val prevTail = chunks[i - 1].text.takeLast(15)
            assertTrue(
                "chunk $i should overlap prev tail",
                chunks[i].text.contains(prevTail.takeLast(5)),
            )
        }
    }

    @Test
    fun `块大小受控`() {
        val text = buildString {
            repeat(200) { i -> append("编号${i}的测试句子，用来验证块长度上界控制。") }
        }
        val chunks = TextChunker.chunk(text, targetChars = 400, overlapChars = 50)
        chunks.forEach { c ->
            assertTrue(
                "chunk ${c.index} too big: ${c.text.length}",
                c.text.length <= 600,
            )
        }
    }

    @Test
    fun `句子边界不被切断`() {
        val text = "第一句完整。第二句也完整。第三句完整收尾。"
        val chunks = TextChunker.chunk(text, targetChars = 12, overlapChars = 0)
        // 极小 target 下句子仍保持完整（不硬切，除非单句超界）
        chunks.forEach { c ->
            assertTrue(c.text.endsWith("。"))
        }
    }

    @Test
    fun `连续覆盖不丢内容`() {
        val text = buildString {
            repeat(50) { i -> append("内容块${i}。") }
        }
        val chunks = TextChunker.chunk(text, targetChars = 100, overlapChars = 10)
        // 第一块以第一句开始，最后一块以最后一句结尾
        assertTrue(chunks.first().text.startsWith("内容块0。"))
        assertTrue(chunks.last().text.contains("内容块49。"))
    }

    @Test
    fun `无边界长句允许硬切`() {
        val text = "a".repeat(1000)
        val chunks = TextChunker.chunk(text, targetChars = 300, overlapChars = 30)
        assertTrue(chunks.size >= 3)
        // 硬切上界 = target*1.5（独立成块，无重叠叠加）
        chunks.forEach { c -> assertTrue(c.text.length <= 460) }
    }
}
