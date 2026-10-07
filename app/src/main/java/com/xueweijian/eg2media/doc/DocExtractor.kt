package com.xueweijian.eg2media.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 文档文本提取：txt/md 直读；PDF 逐页渲染 → ML Kit 中文 OCR（bundled，离线）。
 * 渲染宽 1080px 兼顾 OCR 精度与内存。
 */
object DocExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * @param onPage PDF 页进度回调 (current, total)
     * @return 提取的全文本（页间以换行分隔）
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        mimeType: String?,
        onPage: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): String {
        val name = uri.toString()
        val isPdf = mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        return if (isPdf) extractPdf(context, uri, onPage) else extractText(context, uri)
    }

    private fun extractText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("无法读取文档：$uri")

    private suspend fun extractPdf(
        context: Context,
        uri: Uri,
        onPage: (Int, Int) -> Unit,
    ): String = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val sb = StringBuilder()
        val renderer = PdfRenderer(pfd)
        try {
            val total = renderer.pageCount
            for (i in 0 until total) {
                onPage(i + 1, total)
                val page = renderer.openPage(i)
                try {
                    val w = RENDER_WIDTH
                    val h = (w.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val text = ocr(bmp)
                    bmp.recycle()
                    if (text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(text)
                    }
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer.close()
        }
        sb.toString()
    } ?: error("无法打开 PDF：$uri")

    private suspend fun ocr(bitmap: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private const val RENDER_WIDTH = 1080
}
