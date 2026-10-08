package com.xueweijian.eg2media.search

import android.content.Context
import com.xueweijian.eg2media.core.Hit
import com.xueweijian.eg2media.core.HitMerge
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.RecordIds
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

    private val store = RetrievalStore.get(context)

    companion object {
        /**
         * 显示 uri 解析（v0.21，官方 photoLibraryService.fetchAsset 模式的轻量版）：
         * MediaStore 媒体的 uri 由 id 确定性构造（content://media/external/…/media/{id}），
         * 零查询、永不丢；文档/音频等非 MediaStore 记录回退 metadata 的 uri。
         * 此前只信 metadata——store 层一错位，显示与分数张冠李戴。
         */
        fun resolveDisplayUri(hit: Hit, meta: Map<String, String>): String? = runCatching {
            val ref = RecordIds.decode(hit.recordId)
            when (ref.modality) {
                Modality.IMAGE -> "content://media/external/images/media/${ref.sourceId}"
                Modality.VIDEO_FRAME, Modality.VIDEO_AUDIO ->
                    "content://media/external/video/media/${ref.sourceId}"
                else -> meta["uri"]?.takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: meta["uri"]?.takeIf { it.isNotBlank() }
    }

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
        // v0.21（官方 SemanticRetrievalService:628 同款）：store.get(ids) 返回顺序不可信，
        // 必须 associateBy 按 id 关联。此前按位置配对（getOrElse(i)）导致 meta 张冠李戴
        // （缩略图与分数对不上）+ 越界条目静默丢弃（结果只剩个位数）。
        val metaMap = store.getMetaMap(merged.map { it.recordId })
        return merged.map { h -> MetaHit(h, metaMap[h.recordId] ?: emptyMap()) }
    }

    fun indexedIds(): Set<String> = store.indexedIds()

    /** store 为进程级单例（v0.19 并发修复），engine 关闭不再关 store——空实现保接口 */
    override fun close() {}
}
