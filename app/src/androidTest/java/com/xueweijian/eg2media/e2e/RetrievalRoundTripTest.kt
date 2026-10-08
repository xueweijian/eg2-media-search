package com.xueweijian.eg2media.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.Prompts
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.doc.DocIndexer
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.search.SearchEngine
import com.xueweijian.eg2media.store.RetrievalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 端到端入库-检索闭环（真实 SqliteVectorStore + 真实 embedding）。
 * 文本块入库 → 语义查询命中自身；文档全链路（txt→chunk→embed→搜索）。
 */
@RunWith(AndroidJUnit4::class)
class RetrievalRoundTripTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t1_文本入库后语义命中自身() = runBlocking {
        val srcId = "e2e-${UUID.randomUUID().toString().take(8)}"
        val store = RetrievalStore(ctx)
        try {
            val text = "大熊猫在竹林里啃食新鲜竹笋"
            val v = EmbedderManager.embedText(ctx, Prompts.document(title = "动物百科", text = text))
            store.upsert(
                RecordRef(srcId, Modality.DOC_CHUNK, 0, 0),
                Mrl.truncateAndRenormalize(v, RetrievalStore.DEFAULT_DIMS),
                mapOf("uri" to "test://$srcId", "fn" to "动物百科"),
            )
            val q = EmbedderManager.embedQuery(ctx, "熊猫吃竹子")
            val engine = SearchEngineForTest(store)
            val hits = engine.query(q, topK = 5)
            assertTrue("应命中至少 1 条，实际 ${hits.size}", hits.isNotEmpty())
            assertEquals(srcId, hits.first().hit.sourceId)
        } finally {
            store.deleteBySource(srcId, Modality.DOC_CHUNK)
            store.close()
            EmbedderManager.release()
        }
    }

    @Test
    fun t2_txt文档全链路索引与检索() = runBlocking {
        val dir = File(ctx.filesDir, "e2e_docs").apply { mkdirs() }
        val f = File(dir, "测试文档.txt")
        f.writeText(
            buildString {
                repeat(30) { i -> append("第${i}段：雪豹是高山生态系统的顶级捕食者，主要分布在中亚山区。") }
            }
        )
        try {
            val r = DocIndexer(ctx).index(
                uri = android.net.Uri.fromFile(f),
                displayName = f.name,
                mimeType = "text/plain",
            )
            assertTrue("应产出多个块，实际 ${r.chunks}", r.chunks > 1)
            val q = EmbedderManager.embedQuery(ctx, "雪豹生活在什么地方")
            val engine = SearchEngine(ctx)
            try {
                val hits = engine.query(q, topK = 5, modality = Modality.DOC_CHUNK)
                assertTrue("文档检索应命中，实际 ${hits.size}", hits.isNotEmpty())
                assertEquals("测试文档.txt", hits.first().meta["fn"])
            } finally {
                engine.close()
            }
        } finally {
            f.delete()
            EmbedderManager.release()
        }
    }

    /** 用外部 store 构造 SearchEngine（注入测试库名） */
    private class SearchEngineForTest(private val store: RetrievalStore) :
        AutoCloseable {
        fun query(vec768: FloatArray, topK: Int): List<SearchEngine.MetaHit> {
            // 复用 SearchEngine 逻辑但注入 store：直接内联最小实现
            val q512 = com.xueweijian.eg2media.core.Mrl.truncateAndRenormalize(
                vec768, RetrievalStore.DEFAULT_DIMS,
            )
            val raw = store.search(q512, topK = topK)
            val merged = com.xueweijian.eg2media.core.HitMerge.merge(raw)
            val metas = store.getMeta(merged.map { it.recordId })
            return merged.mapIndexed { i, h -> SearchEngine.MetaHit(h, metas.getOrElse(i) { emptyMap() }) }
        }

        override fun close() {}
    }
}
