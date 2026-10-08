package com.xueweijian.eg2media.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.Prompts
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.store.RetrievalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 取证测试（真机"21 张索引只搜出 2 张"问题）：
 * SqliteVectorStore.search 的 topK 语义 —— 低分记录是否也被返回。
 * 断言：topK=10、库里至少 3 条语义无关记录时，必须返回全部 3 条（分数自算余弦，无绝对阈值）。
 * 若失败 = 底层 native 有分数下限（用户实测结果分数从未低于 0.62 的嫌疑验证）。
 */
@RunWith(AndroidJUnit4::class)
class LowScoreReturnTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t1_低分记录也必须返回() = runBlocking {
        val srcId = "e2e-low-${UUID.randomUUID().toString().take(8)}"
        val store = RetrievalStore(ctx)
        val docs = listOf(
            "量子计算机使用量子比特进行并行运算" to "q1",
            "红烧肉需要先用冰糖炒出焦糖色" to "q2",
            "区块链通过分布式账本保证不可篡改" to "q3",
        )
        try {
            docs.forEach { (text, tag) ->
                val v = EmbedderManager.embedText(ctx, Prompts.document(title = tag, text = text))
                store.upsert(
                    RecordRef("$srcId-$tag", Modality.DOC_CHUNK, 0, 0),
                    Mrl.truncateAndRenormalize(v, RetrievalStore.DEFAULT_DIMS),
                    content = text,
                    extraMeta = mapOf("uri" to "test://$srcId-$tag", "fn" to tag),
                )
            }
            val q768 = EmbedderManager.embedQuery(ctx, "大熊猫保护基地在四川")
            val q512 = Mrl.truncateAndRenormalize(q768, RetrievalStore.DEFAULT_DIMS)
            val raw = store.search(q512, topK = 10)
            val mine = raw.filter { it.recordId.startsWith(srcId) }
            assertTrue(
                "topK=10 且库内有 3 条低分记录，必须全部返回；实际返回 ${mine.size}/3" +
                    "（若 <3 = SqliteVectorStore native 层存在分数下限，真机只搜出 2 张的根因）",
                mine.size >= 3,
            )
        } finally {
            // 按 recordId 精确删除（deleteBySource 走 native metadata filter，实测跨类残留）
            val ids = docs.map { (_, tag) ->
                com.xueweijian.eg2media.core.RecordIds.encode(
                    RecordRef("$srcId-$tag", Modality.DOC_CHUNK, 0, 0),
                )
            }
            runCatching { store.delete(ids) }
            store.close()
            EmbedderManager.release()
        }
    }
}
