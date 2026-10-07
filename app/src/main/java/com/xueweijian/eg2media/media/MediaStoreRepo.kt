package com.xueweijian.eg2media.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val dateModifiedMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
)

data class MediaVideo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val dateModifiedMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
)

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
}
