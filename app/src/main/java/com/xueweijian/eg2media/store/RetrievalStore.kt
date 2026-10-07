package com.xueweijian.eg2media.store

import android.content.Context
import com.google.mediapipe.tasks.retrieval.components.SqliteVectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.xueweijian.eg2media.core.Hit
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.RecordIds
import com.xueweijian.eg2media.core.RecordRef

/**
 * 向量库（HANDOFF §3/§4）。
 * 刻意走底层 VectorStore 直插/直查（不用 SemanticRetriever 自动管线）：
 *  - 向量由我们 Mrl 截断到 512d 并重归一化（保命约束 #2）
 *  - 时间戳区间编码在 RecordId（RecordIds），metadata 冗余存 uri 供 UI 直取
 *  - 分数自己用余弦重算，不依赖底层打分实现细节
 */
class RetrievalStore(
    context: Context,
    private val dims: Int = DEFAULT_DIMS,
) : AutoCloseable {

    private val store = SqliteVectorStore(context, DB_NAME, dims)

    fun upsert(ref: RecordRef, vector512: FloatArray, extraMeta: Map<String, String> = emptyMap()) {
        require(vector512.size == dims) { "vector dims ${vector512.size} != $dims" }
        val meta = buildMap {
            put("uri", extraMeta["uri"] ?: "")
            put("mod", ref.modality.code)
            put("t0", ref.startMs.toString())
            put("t1", ref.endMs.toString())
            putAll(extraMeta.filterKeys { it !in setOf("uri", "mod", "t0", "t1") })
        }
        store.upsert(
            listOf(
                RetrievalRecord(
                    RecordIds.encode(ref),
                    emptyList(),
                    vector512,
                    meta,
                )
            )
        )
    }

    fun search(
        queryVector512: FloatArray,
        topK: Int,
        filter: Map<String, String> = emptyMap(),
    ): List<Hit> {
        require(queryVector512.size == dims)
        return store.search(queryVector512, topK, filter).mapNotNull { rec ->
            val ref = runCatching { RecordIds.decode(rec.id) }.getOrNull() ?: return@mapNotNull null
            Hit(
                recordId = rec.id,
                sourceId = ref.sourceId,
                modality = ref.modality,
                startMs = ref.startMs,
                endMs = ref.endMs,
                score = cosine(queryVector512, rec.embeddings),
            )
        }
    }

    fun indexedIds(): Set<String> = store.allRecordIds.toSet()

    /** 按 ID 批量取 metadata（UI 取 uri 用） */
    fun getMeta(ids: List<String>): List<Map<String, String>> =
        store.get(ids).map { it.metadata }

    fun delete(ids: List<String>) = store.delete(ids)

    fun deleteBySource(sourceId: String, modality: Modality) {
        store.delete(mapOf("src" to sourceId, "mod" to modality.code))
    }

    override fun close() = store.close()

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    companion object {
        const val DB_NAME = "eg2_index"
        const val DEFAULT_DIMS = 512
    }
}
