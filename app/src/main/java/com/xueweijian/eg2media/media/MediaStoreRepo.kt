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
     * v0.24：photo picker 自定义 uri 的元数据单查（picker uri 形如
     * content://media/pick/…，末段不是 MediaStore id，必须按 uri 直查）。
     * 查不到（已删除/云文件）返回 null，调用方跳过并留痕。
     */
    fun queryImageByUri(context: Context, uri: Uri): MediaImage? {
        val projection = arrayOf(
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                MediaImage(
                    id = -1L,
                    uri = uri,
                    dateModifiedMs = c.getLong(0) * 1000,
                    sizeBytes = c.getLong(1),
                    mimeType = c.getString(2) ?: "image/jpeg",
                )
            }
        }.getOrNull()
    }

    fun queryVideoByUri(context: Context, uri: Uri): MediaVideo? {
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                MediaVideo(
                    id = -1L,
                    uri = uri,
                    displayName = c.getString(0) ?: uri.lastPathSegment ?: "video",
                    durationMs = c.getLong(1),
                    dateModifiedMs = c.getLong(2) * 1000,
                    sizeBytes = c.getLong(3),
                    mimeType = c.getString(4) ?: "video/mp4",
                )
            }
        }.getOrNull()
    }

    /**
     * v0.24 生效资产集（官方三层语义）：MediaStore 可见 − removed ∪ custom − removed。
     * 索引 worker 与图库/视频网格统一走这里——追加素材后差集自动只 embed 新项。
     */
    fun effectiveImages(context: Context): List<MediaImage> {
        val removed = CustomAssetStore.removedKeys(context)
        val visible = queryImages(context).filter { it.scopeKey !in removed }
        val custom = CustomAssetStore.customUris(context)
            .filter { AssetMerge.customKey(it) !in removed }
            .mapNotNull { u -> runCatching(Uri.parse(u)).getOrNull()?.let { queryImageByUri(context, it) } }
        return AssetMerge.merge(visible, custom) { it.scopeKey }
    }

    fun effectiveVideos(context: Context): List<MediaVideo> {
        val removed = CustomAssetStore.removedKeys(context)
        val visible = queryVideos(context).filter { it.scopeKey !in removed }
        val custom = CustomAssetStore.customUris(context)
            .filter { AssetMerge.customKey(it) !in removed }
            .mapNotNull { u -> runCatching(Uri.parse(u)).getOrNull()?.let { queryVideoByUri(context, it) } }
        return AssetMerge.merge(visible, custom) { it.scopeKey }
    }
}
