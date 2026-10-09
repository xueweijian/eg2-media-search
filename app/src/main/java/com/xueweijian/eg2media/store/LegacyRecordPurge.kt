package com.xueweijian.eg2media.store

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Round 26.5 污染自愈（v0.24.1）：
 * v0.24.0 的 custom 双形态泄漏把同一资产以 "u"+uri 形式重复入库；其中 picker 返回
 * MediaStore 形式 uri（content://media/external/{images|video}/media/{id}）的记录，
 * 在 v0.24.1 归一到数字 id 命名空间后成为孤儿（重复搜索结果/错误 sourceId 命名空间）。
 *
 * 只清库存记录：custom_uris prefs 里的 MediaStore 形式 uri 必须保留——它承载
 * 持久读授权，且修复后 effective* 会以数字 id 正常收录它（worker 差集自动重索引 = 自愈）。
 * 幂等：进程内只跑一次；范围严格限定在该前缀（正常记录不受影响）。
 */
object LegacyRecordPurge {

    private val done = AtomicBoolean(false)

    fun purgeIfNeeded(context: Context) {
        if (!done.compareAndSet(false, true)) return
        runCatching {
            val store = RetrievalStore.get(context)
            val stale = store.indexedIds()
                .filter { it.startsWith("ucontent://media/external/") }
            if (stale.isNotEmpty()) store.delete(stale)
        }
    }
}
