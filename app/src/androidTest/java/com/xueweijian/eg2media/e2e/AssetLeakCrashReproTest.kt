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
 * 回归网（2026-10-09 Round 26.5 破案后转正；原始取证记录见 git log afa43a0）：
 *
 * 已证实根因：Android 10+ 统一 files 表，DATE_MODIFIED/SIZE/MIME_TYPE/DISPLAY_NAME/
 * DURATION 对【任何】媒体 uri 都查得通——v0.24.0 的 effectiveImages/effectiveVideos
 * 按 uri 形式瞎猜类型，custom 资产图视频互串，图库合并集 scopeKey 重复 →
 * LazyVerticalGrid "Key was already used" 闪退（追加即崩 + 每次重开必崩）。
 *
 * 修复：queryCustomAsset 按 mimeType 分发 + MediaStore 形式 uri 归一到数字 id 命名空间。
 * 本测试三层断言：t0 分发原语 / t1 合集无跨集无重复 key / t2 真实组合图库不崩。
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

    /** t0：MIME 分发原语——queryCustomAsset 必须按 mimeType 归类，绝不互串 */
    @Test
    fun t0_查询原语_mime分发正确() {
        val img = insertImage()
        val vid = insertVideo()
        try {
            val imgAsset = MediaStoreRepo.queryCustomAsset(ctx, img)
            val vidAsset = MediaStoreRepo.queryCustomAsset(ctx, vid)
            println("DIAG imgAsset=${imgAsset != null} vidAsset=${vidAsset != null}")
            Assert.assertNotNull("图片 uri 查询失败", imgAsset)
            Assert.assertNotNull("视频 uri 查询失败", vidAsset)
            Assert.assertEquals("image/jpeg", imgAsset!!.mimeType)
            Assert.assertFalse("图片不得归为视频", imgAsset.isVideo)
            Assert.assertNotNull("图片应转出 MediaImage", imgAsset.toMediaImage())
            Assert.assertNull("图片不得转出 MediaVideo", imgAsset.toMediaVideo())
            Assert.assertNotNull("视频应转出 MediaVideo", vidAsset!!.toMediaVideo())
            Assert.assertNull("视频不得转出 MediaImage", vidAsset.toMediaImage())
            // MediaStore 形式 uri 应归一到数字 id 命名空间
            Assert.assertTrue("MediaStore 形式 custom uri 应解析出数字 id", imgAsset.id >= 0)
        } finally {
            cleanup(img, vid)
        }
    }

    /** t1：合集不变量——effectiveImages/effectiveVideos 无跨集泄漏、合并 scopeKey 无重复 */
    @Test
    fun t1_合集_无跨集泄漏_key无重复() = runBlocking {
        val img = insertImage()
        val vid = insertVideo()
        try {
            CustomAssetStore.addCustomUris(ctx, listOf(img, vid))
            val imgs = MediaStoreRepo.effectiveImages(ctx)
            val vids = MediaStoreRepo.effectiveVideos(ctx)
            val keys = imgs.map { it.scopeKey } + vids.map { it.scopeKey }
            println("DIAG effectiveImages=${imgs.size} effectiveVideos=${vids.size} mergedKeys=${keys.size}")
            val imgUris = imgs.map { it.uri }
            val vidUris = vids.map { it.uri }
            // 跨集泄漏 = v0.24.0 崩溃根因，双向都必须为零
            Assert.assertFalse("图片 uri 不得泄漏进视频集", img in vidUris)
            Assert.assertFalse("视频 uri 不得泄漏进图片集", vid in imgUris)
            val dup = keys.groupBy { it }.filterValues { it.size > 1 }.keys
            Assert.assertTrue("合并集 scopeKey 重复=$dup", dup.isEmpty())
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
                val finalItems = vm.items.value
                val dupKeys = finalItems.groupBy { it.scopeKey }.filterValues { it.size > 1 }.keys
                println("DIAG after add ok, items=${finalItems.size} NO CRASH")
                Assert.assertTrue("图库 items scopeKey 重复=$dupKeys", dupKeys.isEmpty())
            } finally {
                cleanup(img2, vid2)
            }
        } finally {
            cleanup(img1, vid1)
        }
    }
}
