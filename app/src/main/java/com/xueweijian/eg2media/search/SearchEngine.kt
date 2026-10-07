package com.xueweijian.eg2media.search

import android.content.Context
import com.xueweijian.eg2media.core.Hit
import com.xueweijian.eg2media.core.HitMerge
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.store.RetrievalStore

/**
 * 共享检索管道（第一性原理：模态只是"向量怎么来的"，检索链路唯一）。
 * 768d 查询向量 → MRL 512d → top-k（模态过滤带余量）→ HitMerge → 关联 metadata。
 */
class SearchEngine(context: Context) : AutoCloseable {

    data class MetaHit(
        val hit: Hit,
        val meta: Map<String, String>,
    )

    private val store = RetrievalStore(context)

    fun query(
        vec768: FloatArray,
        topK: Int = 24,
        modality: Modality? = null,
        excludeUri: String? = null,
    ): List<MetaHit> {
        val q512 = Mrl.truncateAndRenormalize(vec768, RetrievalStore.DEFAULT_DIMS)
        // 模态过滤会丢结果，先取 2 倍候选
        val raw = store.search(q512, topK = if (modality != null) topK * 2 else topK)
        val filtered = raw
            .filter { modality == null || it.modality == modality }
            .filterNot { h -> excludeUri != null && h.recordId.startsWith("$excludeUri|") }
        val merged = HitMerge.merge(filtered)
        val metas = store.getMeta(merged.map { it.recordId })
        return merged.mapIndexed { i, h -> MetaHit(h, metas.getOrElse(i) { emptyMap() }) }
    }

    fun indexedIds(): Set<String> = store.indexedIds()

    override fun close() = store.close()
}
