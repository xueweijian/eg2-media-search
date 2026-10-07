package com.xueweijian.eg2media.doc

import android.content.Context
import android.net.Uri
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.Prompts
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.core.TextChunker
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.store.RetrievalStore

/**
 * 文档索引：提取文本 → 分块 → 逐块 embed（带 Document 前缀）→ upsert。
 * 幂等：重索引同文档先删旧块（upsert 覆盖不够——块数可能变少留下孤儿）。
 */
class DocIndexer(private val context: Context) {

    data class Result(val chunks: Int, val chars: Int)

    /**
     * @param onPage PDF 渲染/OCR 页进度
     * @param onChunk 块索引进度
     */
    suspend fun index(
        uri: Uri,
        displayName: String,
        mimeType: String?,
        onPage: (Int, Int) -> Unit = { _, _ -> },
        onChunk: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val text = DocExtractor.extract(context, uri, mimeType, onPage)
        if (text.isBlank()) return Result(0, 0)
        val chunks = TextChunker.chunk(text)
        if (chunks.isEmpty()) return Result(0, text.length)

        val sourceId = uri.toString()
        val store = RetrievalStore(context)
        try {
            // 清旧块（重索引幂等）
            runCatching { store.deleteBySource(sourceId, Modality.DOC_CHUNK) }
            chunks.forEachIndexed { i, c ->
                val prefixed = Prompts.document(title = displayName, text = c.text)
                val vec768 = EmbedderManager.embedText(context, prefixed)
                store.upsert(
                    RecordRef(sourceId, Modality.DOC_CHUNK, i.toLong(), i.toLong()),
                    Mrl.truncateAndRenormalize(vec768, RetrievalStore.DEFAULT_DIMS),
                    mapOf(
                        "uri" to sourceId,
                        "fn" to displayName,
                        "ci" to i.toString(),
                    ),
                )
                onChunk(i + 1, chunks.size)
            }
        } finally {
            store.close()
        }
        return Result(chunks.size, text.length)
    }
}
