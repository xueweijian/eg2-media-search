package com.xueweijian.eg2media.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xueweijian.eg2media.core.FrameGrid
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.RecordIds
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.media.MediaStoreRepo
import com.xueweijian.eg2media.store.RetrievalStore

/**
 * 视频抽帧索引 Worker（HANDOFF §4）。
 * - FrameGrid 每 4s 一帧（超长自动抽稀，≤240 帧/视频）
 * - 幂等：视频级差集跳过已索引视频；帧级 recordId 比对断点续跑
 * - 旋转矫正（竖拍视频的解码帧是横的，直接 embed 会错）
 * - 双级进度：视频 x/y + 当前视频帧 a/b
 */
class VideoIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!EmbedderManager.isModelReady(context)) return Result.retry()
        // 前台化：与图片 worker 同策略（LMK 防杀 + 进度常驻）
        runCatching {
            setForeground(
                IndexForeground.info(context, IndexForeground.NOTIF_ID_VIDEO, "正在索引视频", "抽帧中…"),
            )
        }
        val store = RetrievalStore(context)
        try {
            val videos = MediaStoreRepo.queryVideos(context)
            val indexedVideoIds = store.indexedIds()
                .filter { it.contains("|${Modality.VIDEO_FRAME.code}|") }
                .map { it.substringBefore('|') }
                .toSet()
            val pending = videos.filter { it.id.toString() !in indexedVideoIds }
            if (pending.isEmpty()) return Result.success(workDataOf(KEY_VDONE to 0, KEY_VTOTAL to 0))

            var vfailed = 0
            pending.forEachIndexed { vi, video ->
                if (isStopped) return Result.retry()
                val ok = indexVideo(context, store, video) { fdone, ftotal ->
                    setProgress(
                        workDataOf(
                            KEY_VDONE to vi + 1,
                            KEY_VTOTAL to pending.size,
                            KEY_FDONE to fdone,
                            KEY_FTOTAL to ftotal,
                            KEY_VFAILED to vfailed,
                        )
                    )
                }
                if (!ok) vfailed++
            }
            return Result.success(
                workDataOf(KEY_VDONE to pending.size, KEY_VTOTAL to pending.size, KEY_VFAILED to vfailed)
            )
        } finally {
            store.close()
            EmbedderManager.release()
        }
    }

    private suspend fun indexVideo(
        context: Context,
        store: RetrievalStore,
        video: com.xueweijian.eg2media.media.MediaVideo,
        onFrame: suspend (Int, Int) -> Unit,
    ): Boolean {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, video.uri)
            val duration = video.durationMs.takeIf { it > 0 }
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
            if (duration <= 0) return false
            val windows = FrameGrid.frames(duration)
            val prefix = "${video.id}|${Modality.VIDEO_FRAME.code}|"
            val existing = store.indexedIds().filter { it.startsWith(prefix) }.toSet()

            windows.forEachIndexed { i, w ->
                val ref = RecordRef(video.id.toString(), Modality.VIDEO_FRAME, w.startMs, w.endMs)
                if (RecordIds.encode(ref) in existing) return@forEachIndexed
                val bmp = decodeFrame(mmr, w.startMs) ?: return@forEachIndexed
                try {
                    val vec = EmbedderManager.embedImage(context, bmp)
                    store.upsert(
                        ref,
                        Mrl.truncateAndRenormalize(vec, RetrievalStore.DEFAULT_DIMS),
                        content = "${video.displayName} @${w.startMs / 1000}s",
                        extraMeta = mapOf(
                            "uri" to video.uri.toString(),
                            "fn" to video.displayName,
                            "dur" to duration.toString(),
                        ),
                    )
                } finally {
                    bmp.recycle()
                }
                onFrame(i + 1, windows.size)
            }
            return true
        } catch (e: Exception) {
            // 单视频失败不拖垮整批，但上报失败计数（不再静默 success）
            android.util.Log.w("VideoIndexWorker", "skip ${video.displayName}: ${e.message}")
            return false
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeFrame(mmr: MediaMetadataRetriever, tMs: Long): Bitmap? {
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val timeUs = tMs * 1000
        val raw: Bitmap? = if (Build.VERSION.SDK_INT >= 27 && vw > 0 && vh > 0) {
            val scale = TARGET_EDGE.toFloat() / maxOf(vw, vh)
            val dw = (vw * scale).toInt().coerceAtLeast(1)
            val dh = (vh * scale).toInt().coerceAtLeast(1)
            runCatching {
                mmr.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh,
                )
            }.getOrNull()
        } else {
            runCatching {
                mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
        }
        if (raw == null || rot == 0) return raw
        // 竖拍视频：解码帧未应用旋转矩阵，矫正后再 embed
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }.getOrNull().also { if (it != raw) raw.recycle() }
    }

    companion object {
        const val KEY_VDONE = "vdone"
        const val KEY_VTOTAL = "vtotal"
        const val KEY_FDONE = "fdone"
        const val KEY_FTOTAL = "ftotal"
        const val KEY_VFAILED = "vfailed"
        const val UNIQUE_NAME = "video-index"
        const val TARGET_EDGE = 512
    }
}
