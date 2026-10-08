package com.xueweijian.eg2media.e2e

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.core.RecordIds
import com.xueweijian.eg2media.core.RecordRef
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.media.ImageLoader
import com.xueweijian.eg2media.search.SearchEngine
import com.xueweijian.eg2media.store.RetrievalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真链路管线测试（v0.22 补盲区）：
 * 既有 e2e 用程序化 Bitmap 直插 store，绕过了 ImageLoader.decode 与 MediaStore——
 * 真机 MIUI 上 BitmapFactory 全败、0 图片入库而测试全绿，正是这个盲区。
 *
 * 本测试走 MediaStore 真实插入 → ImageLoader.decodeDetailed（三级 fallback）
 * → embed → upsert → SearchEngine 检索 → 显示 uri 确定性构造，全链路无一步绕过。
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePipelineTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 确定性图片：青色渐变 + 红圆（程序化，无资产依赖） */
    private fun testBitmap(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        for (y in 0 until size step 8) {
            paint.color = Color.rgb(0, 120 + y / 4, 200 - y / 4)
            canvas.drawRect(0f, y.toFloat(), size.toFloat(), (y + 8).toFloat(), paint)
        }
        paint.color = Color.RED
        canvas.drawCircle(size / 2f, size / 2f, size / 4f, paint)
        return bmp
    }

    @Test
    fun t_mediaStore图片_decode入库_检索命中_显示uri可构造() = runBlocking {
        assumeTrue("模型未就绪", EmbedderManager.isModelReady(ctx))

        // 1. MediaStore 真实插入（JPEG 字节流）
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "e2e_pipeline_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore 插入失败", uri)
        val written = resolver.openOutputStream(uri!!)?.use { out ->
            testBitmap().compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        assertEquals(true, written)

        val sourceId = uri.lastPathSegment!!
        val ref = RecordRef(sourceId, Modality.IMAGE, 0L, 0L)
        val recordId = RecordIds.encode(ref)
        var store: RetrievalStore? = null
        try {
            // 2. decode（三级 fallback 真值——本测试存在的意义）
            val (bmp, reason) = ImageLoader.decodeDetailed(ctx, uri)
            assertNotNull("decode 失败: $reason", bmp)

            // 3. embed + upsert（与 ImageIndexWorker 循环体同款）
            val v = EmbedderManager.embedImage(ctx, bmp!!)
            store = RetrievalStore.get(ctx)
            store.upsert(
                ref,
                Mrl.truncateAndRenormalize(v, RetrievalStore.DEFAULT_DIMS),
                content = uri.toString(),
                extraMeta = mapOf("uri" to uri.toString()),
            )

            // 4. 检索（图搜图路径：同图向量必命中自身第一名）
            val engine = SearchEngine(ctx)
            val hits = engine.query(v, topK = 5)
            assertTrue("检索无命中", hits.isNotEmpty())
            assertEquals("自身应排第一", sourceId, hits.first().hit.sourceId)

            // 5. 显示 uri 由 recordId 确定性构造（v0.21 机制真值）
            val display = SearchEngine.resolveDisplayUri(hits.first().hit, hits.first().meta)
            assertEquals("content://media/external/images/media/$sourceId", display)
            engine.close()
        } finally {
            val s = store
            if (s != null) {
                runCatching { s.delete(listOf(recordId)) }
            }
            runCatching { resolver.delete(uri, null, null) }
            EmbedderManager.release()
        }
    }
}
