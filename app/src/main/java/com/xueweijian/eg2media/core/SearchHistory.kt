package com.xueweijian.eg2media.core

/**
 * 搜索历史（v0.24：搜索框 ✕ 一键清空 + 历史 chips，Google app 同款）。
 * 纯函数：最新在前、去重提升、空白不记、cap 上限。
 */
object SearchHistory {

    fun record(history: List<String>, query: String, max: Int = 5): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return history
        return (listOf(q) + history.filter { it != q }).take(max)
    }

    fun remove(history: List<String>, query: String): List<String> =
        history.filter { it != query }
}
