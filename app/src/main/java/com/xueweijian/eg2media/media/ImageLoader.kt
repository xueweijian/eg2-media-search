package com.xueweijian.eg2media.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size

/**
 * 下采样解码（v0.22 对齐 Edge Gallery PhotoLibraryService.loadBitmap）：
 *
 * 官方 content-uri 图片解码是两级 fallback：
 *   1. ImageDecoder + ALLOCATOR_SOFTWARE（硬件位图在部分机型/云占位上会失败）
 *   2. loadThumbnail（MediaProvider 内部缓存，几乎必成）
 *
 * 我们的旧实现只有 BitmapFactory.decodeStream 一条路，MIUI 上对 MediaStore
 * uri 大面积返回 null → 图片索引静默全跳过 → 库里只有视频帧（真机 v0.21 实证）。
 */
object ImageLoader {

    /** 解码（官方三级 fallback，索引用） */
    fun decode(context: Context, uri: Uri, targetEdge: Int = 768): Bitmap? {
        decodeImageDecoder(context, uri, targetEdge)?.let { return it }
        decodeThumbnail(context, uri, targetEdge)?.let { return it }
        return decodeBitmapFactory(context, uri, targetEdge)
    }

    /** 解码 + 首个失败原因（可观测：索引失败计数与日志用） */
    fun decodeDetailed(context: Context, uri: Uri, targetEdge: Int = 768): Pair<Bitmap?, String?> {
        val reasons = mutableListOf<String>()
        decodeImageDecoder(context, uri, targetEdge)?.let { return it to null }
        reasons += "ImageDecoder失败"
        decodeThumbnail(context, uri, targetEdge)?.let { return it to null }
        reasons += "loadThumbnail失败"
        decodeBitmapFactory(context, uri, targetEdge)?.let { return it to null }
        reasons += "BitmapFactory失败"
        return null to reasons.joinToString("→")
    }

    /** 官方主路径：ImageDecoder + 强制软件位图 + 精确 targetSize */
    private fun decodeImageDecoder(context: Context, uri: Uri, targetEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > targetEdge) {
                    val scale = targetEdge.toFloat() / maxDim
                    decoder.setTargetSize(
                        maxOf(1, (info.size.width * scale).toInt()),
                        maxOf(1, (info.size.height * scale).toInt()),
                    )
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    /** 官方兜底：MediaProvider 缩略图（云图/HEIC 几乎必成） */
    private fun decodeThumbnail(context: Context, uri: Uri, targetEdge: Int): Bitmap? =
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(targetEdge, targetEdge), null)
        }.getOrNull()

    /** 旧路径保留为最后兜底 */
    private fun decodeBitmapFactory(context: Context, uri: Uri, targetEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetEdge / 2 &&
            bounds.outHeight / (sample * 2) >= targetEdge / 2
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
