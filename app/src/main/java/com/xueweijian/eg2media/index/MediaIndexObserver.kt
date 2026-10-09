package com.xueweijian.eg2media.index

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.xueweijian.eg2media.core.ChangeAggregator
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.media.CustomAssetStore
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.media.ImageLoader
import com.xueweijian.eg2media.media.MediaStoreRepo
import com.xueweijian.eg2media.store.RetrievalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 增量索引（HANDOFF §4）：ContentObserver 监听图片表变更。
 * - onChange 风暴经 ChangeAggregator 去重合并（1.5s 窗）
 * - 单张重索引 = upsert 覆盖（照片被编辑后时间戳变化也会触发，覆盖旧向量正好）
 * - 模型未就绪时跳过；漏掉的由 WorkManager 全量差集兜底
 */
class MediaIndexObserver(private val context: Context) : ContentObserver(null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val aggregator = ChangeAggregator<Long>(
        windowMs = 1_500,
        scope = scope,
        flush = { ids -> indexBatch(ids) },
    )

    fun register() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyDescendants = */ true,
            this,
        )
    }

    fun unregister() {
        runCatching { context.contentResolver.unregisterContentObserver(this) }
        scope.cancel()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val id = uri?.lastPathSegment?.toLongOrNull() ?: return
        if (id > 0) aggregator.offer(id)
    }

    private suspend fun indexBatch(ids: List<Long>) {
        if (!EmbedderManager.isModelReady(context)) {
            Log.d(TAG, "model not ready, skip ${ids.size} changes (full index will catch up)")
            return
        }
        val store = RetrievalStore.get(context)
        try {
            for (id in ids) {
                // v0.24：被移除的资产不重索引（移除后照片编辑触发 observer 的场景）
                if (id.toString() in CustomAssetStore.removedKeys(context)) continue
                val img = MediaStoreRepo.queryImageById(context, id) ?: continue
                val bmp = ImageLoader.decode(context, img.uri) ?: continue
                try {
                    val vec = EmbedderManager.embedImage(context, bmp)
                    store.upsert(
                        RecordRef(id.toString(), Modality.IMAGE, 0L, 0L),
                        Mrl.truncateAndRenormalize(vec, RetrievalStore.DEFAULT_DIMS),
                        content = img.uri.toString(),
                        extraMeta = mapOf("uri" to img.uri.toString()),
                    )
                } finally {
                    bmp.recycle()
                }
            }
            Log.d(TAG, "incremental indexed ${ids.size} images")
        } catch (e: Exception) {
            Log.w(TAG, "incremental index failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "MediaIndexObserver"
    }
}
