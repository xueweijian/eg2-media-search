package com.xueweijian.eg2media.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** 下采样解码：EG2 视觉塔输入有官方 resize，这里控制解码内存（长边 ~768px 足够） */
object ImageLoader {

    fun decode(context: Context, uri: Uri, targetEdge: Int = 768): Bitmap? {
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

    /** 解码 + 明确失败原因（图搜图错误细化用）：bitmap 为 null 时 reason 非空 */
    fun decodeDetailed(context: Context, uri: Uri, targetEdge: Int = 768): Pair<Bitmap?, String?> {
        val r = context.contentResolver
        val stream = try {
            r.openInputStream(uri)
        } catch (e: Exception) {
            return null to "打开文件流失败（${e.javaClass.simpleName}）"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null to "无法打开文件流（图片可能已被移动/删除，或云端未下载）"
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null to "图片格式无法解码（${bounds.outMimeType ?: "未知格式"}）——云端图片请先在相册里打开一次"
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetEdge / 2 &&
            bounds.outHeight / (sample * 2) >= targetEdge / 2
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = try {
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            return null to "解码失败（${e.javaClass.simpleName}）"
        }
        return bmp to if (bmp == null) "解码返回空" else null
    }
}
