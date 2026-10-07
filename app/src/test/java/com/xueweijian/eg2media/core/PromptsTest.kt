package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HANDOFF §2 任务前缀：文本必加，媒体不加。
 * 格式以官方模型卡/开发者指南为准，测试锁行为防漂移。
 */
class PromptsTest {

    @Test
    fun `检索 query 加 SearchQuery 前缀`() {
        assertEquals("SearchQuery: 黄色的花", Prompts.searchQuery("黄色的花"))
    }

    @Test
    fun `无标题文档 title 为 none`() {
        assertEquals("title: none | text: 第三章 向量检索", Prompts.document(null, "第三章 向量检索"))
    }

    @Test
    fun `有标题文档带标题`() {
        assertEquals("title: 课堂笔记 | text: 今日讲熵", Prompts.document("课堂笔记", "今日讲熵"))
    }

    @Test
    fun `空 query 仍保持前缀格式不崩`() {
        assertEquals("SearchQuery: ", Prompts.searchQuery(""))
    }
}
