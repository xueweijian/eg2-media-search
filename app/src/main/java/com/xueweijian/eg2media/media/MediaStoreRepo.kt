package com.xueweijian.eg2media.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.xueweijian.eg2media.core.AssetMerge

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val dateModifiedMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
) {
    /** 资产 scopeKey：MediaStore=纯数字 id（兼容既有库），custom="u"+uri */
    val scopeKey: String get() = if (id >= 0) id.toString() else AssetMerge.customKey(uri.toString())
    val isCustom: Boolean get() = id < 0
}

data class MediaVideo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val dateModifiedMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
) {
    val scopeKey: String get() = if (id >= 0) id.toString() else AssetMerge.customKey(uri.toString())
    val isCustom: Boolean get() = id < 0
}

/** 素材接入层（HANDOFF §4）：图片/视频走 MediaStore，ContentObserver 增量后置 */
object MediaStoreRepo {

    fun queryImages(context: Context, sinceModifiedMs: Long = 0L): List<MediaImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = if (sinceModifiedMs > 0) "${MediaStore.Images.Media.DATE_MODIFIED} > ?" else null
        val args = if (sinceModifiedMs > 0) arrayOf((sinceModifiedMs / 1000).toString()) else null
        val out = mutableListOf<MediaImage>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    MediaImage(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        dateModifiedMs = c.getLong(dateCol) * 1000,
                        sizeBytes = c.getLong(sizeCol),
                        mimeType = c.getString(mimeCol) ?: "image/jpeg",
                    )
                )
            }
        }
        return out
    }

    fun queryVideos(context: Context): List<MediaVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
        )
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val out = mutableListOf<MediaVideo>()
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    MediaVideo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = c.getString(nameCol) ?: "video$id",
                        durationMs = c.getLong(durCol),
                        dateModifiedMs = c.getLong(dateCol) * 1000,
                        sizeBytes = c.getLong(sizeCol),
                        mimeType = c.getString(mimeCol) ?: "video/mp4",
                    )
                )
            }
        }
        return out
    }

    /** 单条查询（增量索引用：ContentObserver 变更 uri 的 lastPathSegment = MediaStore id） */
    fun queryImageById(context: Context, id: Long): MediaImage? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            return MediaImage(
                id = c.getLong(0),
                uri = uri,
                dateModifiedMs = c.getLong(1) * 1000,
                sizeBytes = c.getLong(2),
                mimeType = c.getString(3) ?: "image/jpeg",
            )
        }
        return null
    }

    /**
     * v0.24.1（Round 26.5 根因修复）：custom uri 单查 + MIME 分发。
     *
     * Android 10+ 统一 files 表：DATE_MODIFIED/SIZE/MIME_TYPE 等是通用列，
     * 对【任何】媒体 uri 都查得通——按 uri 形式猜图/视频必然互串
     * （v0.24.0 每个 custom 资产在 effectiveImages/effectiveVideos 各出现一次，
     * scopeKey 相同 → 图库 LazyVerticalGrid duplicate key 闪退，模拟器全栈复现实锤）。
     * 现在按查询到的 mimeType 决定归属：video/ 前缀只进视频集，其余只进图片集。
     *
     * 基础列（mime/date/size）对所有 uri 形式可靠；displayName/duration 仅视频
     * 二次查询（picker uri 的列支持面不明，失败给默认值不阻断）。
     */
    data class CustomAsset(
        val uri: Uri,
        val isVideo: Boolean,
        /** MediaStore 形式 uri → 真实数字 id（并入可见集命名空间）；picker 形式 → -1 */
        val id: Long,
        val dateModifiedMs: Long,
        val sizeBytes: Long,
        val mimeType: String,
        val displayName: String,
        val durationMs: Long,
    ) {
        fun toMediaImage(): MediaImage? =
            if (isVideo || mimeType.startsWith("video/")) null else MediaImage(
                id = id, uri = uri, dateModifiedMs = dateModifiedMs,
                sizeBytes = sizeBytes, mimeType = mimeType,
            )

        fun toMediaVideo(): MediaVideo? =
            if (!isVideo) null else MediaVideo(
                id = id, uri = uri, displayName = displayName,
                durationMs = durationMs, dateModifiedMs = dateModifiedMs,
                sizeBytes = sizeBytes, mimeType = mimeType,
            )
    }

    fun queryCustomAsset(context: Context, uri: Uri): CustomAsset? {
        val baseProjection = arrayOf(
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        val base = runCatching {
            context.contentResolver.query(uri, baseProjection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Triple(
                    c.getString(0)?.lowercase(),
                    c.getLong(1) * 1000,
                    c.getLong(2),
                )
            }
        }.getOrNull() ?: return null
        val (mimeRaw, dateMs, sizeBytes) = base
        // 无 mime 无法分发（云占位/已删除），跳过——绝不猜类型
        val mime = mimeRaw ?: return null
        if (!mime.startsWith("video/") && !mime.startsWith("image/")) return null

        val isVideo = mime.startsWith("video/")
        var displayName: String = uri.lastPathSegment ?: "asset"
        var durationMs = 0L
        if (isVideo) {
            // displayName/duration 仅视频二次查询（picker uri 列支持面不明，失败给默认值不阻断）
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { displayName = it }
                        durationMs = c.getLong(1)
                    }
                }
            }
        }
        return CustomAsset(
            uri = uri,
            isVideo = isVideo,
            id = com.xueweijian.eg2media.core.AssetMerge.mediaStoreUriId(uri.toString()) ?: -1L,
            dateModifiedMs = dateMs,
            sizeBytes = sizeBytes,
            mimeType = mime,
            displayName = displayName,
            durationMs = durationMs,
        )
    }

    /**
     * v0.24 生效资产集（官方三层语义）：MediaStore 可见 − removed ∪ custom − removed。
     * 索引 worker 与图库/视频网格统一走这里——追加素材后差集自动只 embed 新项。
     * v0.24.1：custom 走 queryCustomAsset MIME 分发（图/视频不再互串）；
     * removed 比对用归一 key（MediaStore 形式 custom uri = 数字 id 命名空间）。
     */
    fun effectiveImages(context: Context): List<MediaImage> {
        val removed = CustomAssetStore.removedKeys(context)
        val visible = queryImages(context).filter { it.scopeKey !in removed }
        val custom = CustomAssetStore.customUris(context)
            .mapNotNull { u -> runCatching { Uri.parse(u) }.getOrNull() }
            .filter { AssetMerge.normalizedCustomKey(it.toString()) !in removed }
            .mapNotNull { u -> queryCustomAsset(context, u) }
            .mapNotNull { it.toMediaImage() }
        return AssetMerge.merge(visible, custom) { it.scopeKey }
    }

    fun effectiveVideos(context: Context): List<MediaVideo> {
        val removed = CustomAssetStore.removedKeys(context)
        val visible = queryVideos(context).filter { it.scopeKey !in removed }
        val custom = CustomAssetStore.customUris(context)
            .mapNotNull { u -> runCatching { Uri.parse(u) }.getOrNull() }
            .filter { AssetMerge.normalizedCustomKey(it.toString()) !in removed }
            .mapNotNull { u -> queryCustomAsset(context, u) }
            .mapNotNull { it.toMediaVideo() }
        return AssetMerge.merge(visible, custom) { it.scopeKey }
    }
}
