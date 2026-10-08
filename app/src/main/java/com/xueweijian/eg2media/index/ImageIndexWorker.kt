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
        val store = RetrievalStore.get(context)
        try {
            val all = MediaStoreRepo.queryImages(context)
            val indexed = store.indexedIds()
            val pending = all.filter {
                RecordIdsKey(it.id.toString()) !in indexed
            }
            if (pending.isEmpty()) return Result.success(workDataOf(KEY_DONE to 0, KEY_TOTAL to 0))

            // 前台化：LMK 不杀 + 通知栏常驻进度。挪到差集确认之后——
            // 追加语义下秒退的空跑不再弹前台通知（v0.19 反复索引观感修复）
            runCatching {
                setForeground(
                    IndexForeground.info(context, IndexForeground.NOTIF_ID_IMAGE, "正在索引图片", "准备中…"),
                )
            }

            var done = 0
            var failed = 0
            val failReasons = mutableListOf<String>()
            for (batch in pending.chunked(BATCH)) {
                if (isStopped) return Result.retry()
                for (img in batch) {
                    // v0.22：三级 fallback 解码（官方 loadBitmap 同款）+ 失败必须留痕
                    // （旧实现 decode ?: continue 静默吞——MIUI 上 BitmapFactory 全败、
                    //   0 图片入库而 UI 仍显示"索引就绪 0 失败"，观测盲区是真凶）
                    val (bmp, reason) = ImageLoader.decodeDetailed(context, img.uri)
                    if (bmp == null) {
                        failed++
                        val msg = "img ${img.id}: $reason"
                        android.util.Log.w("ImageIndexWorker", "skip $msg")
                        if (failReasons.size < 5) failReasons.add(msg)
                        continue
                    }
                    try {
                        val vec = EmbedderManager.embedImage(context, bmp)
                        val ref = RecordRef(img.id.toString(), Modality.IMAGE, 0L, 0L)
                        store.upsert(
                            ref,
                            Mrl.truncateAndRenormalize(vec, RetrievalStore.DEFAULT_DIMS),
                            content = img.uri.toString(),
                            extraMeta = mapOf("uri" to img.uri.toString()),
                        )
                    } catch (e: Exception) {
                        // 单图失败不拖垮整批，但计数上报（损坏文件 / 编解码异常常见）
                        android.util.Log.w("ImageIndexWorker", "skip ${img.id}: ${e.message}")
                        failed++
                    } finally {
                        bmp.recycle()
                    }
                }
                done += batch.size
                setProgress(
                    workDataOf(
                        KEY_DONE to done,
                        KEY_TOTAL to pending.size,
                        KEY_FAILED to failed,
                    )
                )
            }
            // 真值快照：索引后库里到底有什么（总数+模态分布）随 output 上屏
            val lib = runCatching { store.stats() }.getOrNull()
            val libSummary = lib?.let { s ->
                "共${s.total}条 " + s.byModality.entries.joinToString(" ") { "${it.key}:${it.value}" }
            } ?: "库统计不可用"
            android.util.Log.i("ImageIndexWorker", "done=$done failed=$failed lib=$libSummary failReasons=$failReasons")
            return Result.success(
                workDataOf(
                    KEY_DONE to done,
                    KEY_TOTAL to pending.size,
                    KEY_FAILED to failed,
                    KEY_LIB to libSummary,
                )
            )
        } finally {
            // store 是进程级单例不关闭（v0.19 并发修复）；只释放推理引擎
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
        const val KEY_FAILED = "failed"
        const val KEY_LIB = "lib"
        const val BATCH = 50
        const val UNIQUE_NAME = "image-index"
    }
}
