package com.xueweijian.eg2media.e2e

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xueweijian.eg2media.media.CustomAssetStore
import com.xueweijian.eg2media.media.MediaStoreRepo
import com.xueweijian.eg2media.ui.GalleryGridInline
import com.xueweijian.eg2media.ui.GalleryViewModel
import com.xueweijian.eg2media.ui.theme.EG2MediaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 诊断测试（2026-10-09 真机 v0.24 "追加素材后经常闪退"取证，Round 26）：
 *
 * 假设链：
 * 1. 管理菜单 PickVisualMedia.ImageAndVideo 追加的 custom_uris 图片视频混存；
 * 2. effectiveImages 对【所有】custom uri 跑 queryImageByUri（DATE_MODIFIED/SIZE/MIME_TYPE
 *    为 files 表通用列，对视频 uri 同样查得通）→ 视频 uri 以"图片形态"混入图片集；
 *    对称地 queryVideoByUri 对图片 uri（display_name/duration/... 统一表时代同样通用）；
 * 3. GalleryViewModel 合并 imgs+vids 进同一个 LazyVerticalGrid，
 *    同一 custom uri 两条记录 scopeKey 相同（"u"+uri）→ duplicate key →
 *    measure 阶段 item 子组合抛 IllegalArgumentException → 与真机栈
 *    LazyLayoutBeyondBoundsModifierLocal.measure 吻合。
 *
 * 本测试只取证不改产品代码：t0 验证查询原语泄漏；t1 验证合并集 key 重复；
 * t2 真实组合 GalleryGridInline 复现崩溃（若崩溃，CI 日志即完整栈）。
 */
@RunWith(AndroidJUnit4::class)
class AssetLeakCrashReproTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createComposeRule()

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

    private fun insertImage(): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "diag_leak_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!.also { uri ->
            resolver.openOutputStream(uri)!!.use { testBitmap().compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }

    private fun insertVideo(): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "diag_leak_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!.also { uri ->
            // 垃圾字节即可——本测试不解码视频，只验证列查询与合并逻辑
            resolver.openOutputStream(uri)!!.use { it.write(ByteArray(2048)) }
        }
    }

    private fun cleanup(vararg uris: Uri) {
        for (u in uris) runCatching { ctx.contentResolver.delete(u, null, null) }
        // 清 custom/removed prefs，避免污染其他测试
        runCatching {
            val prefs = ctx.getSharedPreferences("custom_assets", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }
    }

    /** t0：查询原语泄漏——通用列查询对"异类" uri 是否放行 */
    @Test
    fun t0_查询原语_视频uri能否被当图片查通() {
        val img = insertImage()
        val vid = insertVideo()
        try {
            val vidAsImage = MediaStoreRepo.queryImageByUri(ctx, vid)
            val imgAsVideo = MediaStoreRepo.queryVideoByUri(ctx, img)
            println("DIAG vidAsImage=${vidAsImage != null} imgAsVideo=${imgAsVideo != null}")
            println("DIAG vidAsImage.scopeKey=${vidAsImage?.scopeKey}")
            println("DIAG imgAsVideo.scopeKey=${imgAsVideo?.scopeKey}")
            // 假设成立 = 两者均非 null（不 assert 失败——本测试目标是取证，打印即证据）
        } finally {
            cleanup(img, vid)
        }
    }

    /** t1：合并集 key 重复——effectiveImages ∪ effectiveVideos 中同一 custom uri 是否双现 */
    @Test
    fun t1_合并集_同一custom_uri双现_key重复() = runBlocking {
        val img = insertImage()
        val vid = insertVideo()
        try {
            CustomAssetStore.addCustomUris(ctx, listOf(img, vid))
            val imgs = MediaStoreRepo.effectiveImages(ctx)
            val vids = MediaStoreRepo.effectiveVideos(ctx)
            val keys = (imgs + vids).map { it.scopeKey }
            println("DIAG effectiveImages=${imgs.size} effectiveVideos=${vids.size} mergedKeys=${keys.size}")
            println("DIAG imgUri in images=${imgs.any { it.uri == img }} imgUri in videos=${vids.any { it.uri == img }}")
            println("DIAG vidUri in images=${imgs.any { it.uri == vid }} vidUri in videos=${vids.any { it.uri == vid }}")
            val dup = keys.groupBy { it }.filterValues { it.size > 1 }.keys
            println("DIAG duplicatedKeys=${dup.toList()}")
            if (dup.isNotEmpty()) {
                Assert.fail("假设证实：图库合并集存在重复 scopeKey → LazyVerticalGrid duplicate key 崩溃源。dup=$dup")
            }
        } finally {
            cleanup(img, vid)
        }
    }

    /** t2：真实组合 GalleryGridInline——追加素材后图库是否崩溃（完整栈在 CI logcat） */
    @Test
    fun t2_图库组合_追加素材后不崩溃() = runBlocking {
        val img1 = insertImage()
        val vid1 = insertVideo()
        val vm = GalleryViewModel(ctx.applicationContext as android.app.Application)
        try {
            composeRule.setContent {
                EG2MediaTheme {
                    GalleryGridInline(vm = vm)
                }
            }
            // 首次渲染（干净 prefs）
            withTimeout(20_000) { while (vm.items.value.isEmpty()) delay(200) }
            composeRule.waitForIdle()
            println("DIAG first render ok, items=${vm.items.value.size}")

            // 模拟管理菜单「添加更多照片/视频」：picker 返回 图+视频 混合批次
            val img2 = insertImage()
            val vid2 = insertVideo()
            try {
                composeRule.runOnUiThread { vm.addCustom(listOf(img1, vid1, img2, vid2)) }
                // 等 refreshAfterAssetChange 完成（items 清空→重填）
                withTimeout(20_000) {
                    while (vm.items.value.size < 4) delay(200)
                }
                composeRule.waitForIdle()
                Thread.sleep(1500) // 给重组/测量留时间——若 key 冲突，这里就是崩溃点
                println("DIAG after add ok, items=${vm.items.value.size} NO CRASH")
            } finally {
                cleanup(img2, vid2)
            }
        } finally {
            cleanup(img1, vid1)
        }
    }
}
