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

    companion object {
        const val DB_NAME = "eg2_index"
        const val DEFAULT_DIMS = 512

        /** 全量余弦搜索时单批 get 的记录数（控制 JNI 往返与内存峰值） */
        private const val GET_BATCH = 500

        /**
         * 进程级单例（对齐 Edge Gallery SemanticRetrievalService：vectorStore by lazy，
         * 全 app 共享一个实例）。此前 worker / observer / SearchEngine / DocIndexer 各自
         * new SqliteVectorStore 打开同一 db 文件并发读写——索引中搜索的闪退候选根因。
         * close() 置空单例，下次 get() 重建（e2e 测试依赖此语义）。
         */
        @Volatile
        private var instance: RetrievalStore? = null

        fun get(context: Context): RetrievalStore =
            instance ?: synchronized(this) {
                instance ?: RetrievalStore(context.applicationContext).also { instance = it }
            }
    }

    fun upsert(
        ref: RecordRef,
        vector512: FloatArray,
        content: String,
        extraMeta: Map<String, String> = emptyMap(),
    ) {
        require(vector512.size == dims) { "vector dims ${vector512.size} != $dims" }
        require(content.isNotBlank()) { "content required: SqliteVectorStore rejects empty content" }
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
                    listOf(com.google.mediapipe.tasks.core.TextPart(content)),
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
        // 纯 Kotlin 全量余弦（对齐 google-ai-edge/gallery 的 SemanticRetrievalService）。
        // 不再用 nativeGetNearestRecords：其 native 层行为黑盒，实测 21 条低分记录只返回
        // 2 条且分数从不低于 ~0.62（内部相似度下限），而本 app 场景要求全量排序不丢结果。
        // 规模预算：5 万条 × 512d ≈ 25ms 余弦；分批 get 控制 JNI/内存峰值。
        val ids = store.allRecordIds
        if (ids.isEmpty()) return emptyList()
        val scored = ArrayList<Hit>(ids.size)
        for (chunk in ids.chunked(GET_BATCH)) {
            for (rec in store.get(chunk)) {
                val ref = runCatching { RecordIds.decode(rec.id) }.getOrNull() ?: continue
                if (filter.isNotEmpty()) {
                    val meta = rec.metadata ?: continue
                    val ok = filter.all { (k, v) -> meta[k] == v }
                    if (!ok) continue
                }
                val emb = rec.embeddings
                if (emb.isEmpty()) continue
                scored += Hit(
                    recordId = rec.id,
                    sourceId = ref.sourceId,
                    modality = ref.modality,
                    startMs = ref.startMs,
                    endMs = ref.endMs,
                    score = cosine(queryVector512, emb),
                )
            }
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    fun indexedIds(): Set<String> = store.allRecordIds.toSet()

    data class StoreStats(
        val total: Int,
        val byModality: Map<String, Int>,
        val samples: Map<String, List<String>>,
    )

    /** 库统计（诊断用）：总数 + 按模态计数 + 每模态样本 recordId——远程判库 pollution */
    fun stats(): StoreStats {
        val ids = store.allRecordIds
        val by = mutableMapOf<String, Int>()
        val samples = mutableMapOf<String, MutableList<String>>()
        for (id in ids) {
            // recordId 形如 "src|mod|t0-t1"，第二段即模态 code
            val mod = id.split('|').getOrNull(1) ?: "?"
            by.merge(mod, 1, Int::plus)
            samples.getOrPut(mod) { mutableListOf() }.also { if (it.size < 3) it.add(id) }
        }
        return StoreStats(ids.size, by, samples)
    }

    /** 清空全部记录（官方 deleteAllRecords 模式：按 id 全删）——重建索引入口用 */
    fun deleteAll() {
        val ids = store.allRecordIds
        if (ids.isNotEmpty()) store.delete(ids)
    }

    /** 按 ID 批量取 metadata（UI 取 uri 用） */
    fun getMeta(ids: List<String>): List<Map<String, String>> =
        store.get(ids).map { it.metadata }

    /** v0.21：按 recordId 关联的 metadata（native get 返回顺序不可信，见 SearchEngine.query 注释） */
    fun getMetaMap(ids: List<String>): Map<String, Map<String, String>> =
        store.get(ids).mapNotNull { r -> r.metadata?.let { r.id to it } }.toMap()

    fun delete(ids: List<String>) = store.delete(ids)

    fun deleteBySource(sourceId: String, modality: Modality) {
        // upsert 时 meta["uri"]=sourceId（文档场景 uri 即 sourceId）
        store.delete(mapOf("uri" to sourceId, "mod" to modality.code))
    }

    override fun close() {
        store.close()
        instance = null
    }

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
}
