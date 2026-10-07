package com.xueweijian.eg2media.core

/**
 * 任务前缀（HANDOFF §2）：文本输入必加，媒体输入不加。
 * 格式锁定官方模型卡约定，若官方变更以模型卡为准并同步改测试。
 */
object Prompts {

    /** 检索 query 前缀 */
    fun searchQuery(query: String): String = "SearchQuery: $query"

    /** 入库文档前缀；无标题用 title: none */
    fun document(title: String?, text: String): String =
        "title: ${title ?: "none"} | text: $text"
}
