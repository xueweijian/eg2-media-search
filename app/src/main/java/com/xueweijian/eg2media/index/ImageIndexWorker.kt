package com.xueweijian.eg2media.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.media.ImageLoader
import com.xueweijian.eg2media.media.MediaStoreRepo
import com.xueweijian.eg2media.store.RetrievalStore

/**
 * 图片索引 Worker（HANDOFF §4）。
 * 幂等 = indexedIds() 差集；断点续跑 = 每批 upsert 即提交；
 * 约束（充电+空闲）由调度方设置；停止信号 = isStopped。
 */
class ImageIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!EmbedderManager.isModelReady(context)) return Result.retry()
        val store = RetrievalStore(context)
        try {
            val all = MediaStoreRepo.queryImages(context)
            val indexed = store.indexedIds()
            val pending = all.filter {
                RecordIdsKey(it.id.toString()) !in indexed
            }
            if (pending.isEmpty()) return Result.success(workDataOf(KEY_DONE to 0, KEY_TOTAL to 0))

            var done = 0
            for (batch in pending.chunked(BATCH)) {
                if (isStopped) return Result.retry()
                for (img in batch) {
                    val bmp = ImageLoader.decode(context, img.uri) ?: continue
                    try {
                        val vec = EmbedderManager.embedImage(context, bmp)
                        val ref = RecordRef(img.id.toString(), Modality.IMAGE, 0L, 0L)
                        store.upsert(
                            ref,
                            Mrl.truncateAndRenormalize(vec, RetrievalStore.DEFAULT_DIMS),
                            mapOf("uri" to img.uri.toString()),
                        )
                    } finally {
                        bmp.recycle()
                    }
                }
                done += batch.size
                setProgress(
                    workDataOf(
                        KEY_DONE to done,
                        KEY_TOTAL to pending.size,
                    )
                )
            }
            return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to pending.size))
        } finally {
            store.close()
            EmbedderManager.release()
        }
    }

    private fun RecordIdsKey(sourceId: String): String =
        com.xueweijian.eg2media.core.RecordIds.encode(
            RecordRef(sourceId, Modality.IMAGE, 0L, 0L)
        )

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val BATCH = 50
        const val UNIQUE_NAME = "image-index"
    }
}
