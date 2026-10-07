package com.xueweijian.eg2media.embed

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.mediapipe.tasks.components.containers.EmbeddingResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.xueweijian.eg2media.core.DeviceRouter
import com.xueweijian.eg2media.core.ModelRoute
import com.xueweijian.eg2media.core.Prompts
import java.io.File

/**
 * EG2 推理管理器（HANDOFF §2 两模型制：740M 常驻管检索）。
 * - 模型文件路径 = app 私有目录/models/{DeviceRouter 路由的文件名}
 * - GPU delegate 初始化失败自动降 CPU（HANDOFF §0 GPU→CPU fallback 必做）
 * - 输出 768d 已 L2 归一化向量；入库前由调用方做 Mrl 截断
 */
object EmbedderManager {

    @Volatile
    private var embedder: UniversalEmbedder? = null

    private var activeDelegate: Delegate = Delegate.GPU

    fun route(context: Context): ModelRoute {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return DeviceRouter.route(
            socModel = Build.SOC_MODEL,
            lowRam = am.isLowRamDevice,
            memoryClassMb = am.memoryClass,
        )
    }

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), route(context).eg2File)

    fun isModelReady(context: Context): Boolean = modelFile(context).length() > 0

    @Synchronized
    fun ensure(context: Context): UniversalEmbedder {
        embedder?.let { return it }
        val file = modelFile(context)
        check(file.exists() && file.length() > 0) {
            "EG2 model missing at $file — push or download ${route(context).eg2File} first"
        }
        val created = try {
            create(context, file.absolutePath, Delegate.GPU).also { activeDelegate = Delegate.GPU }
        } catch (e: Throwable) {
            // GPU 驱动碎片化兜底（HANDOFF §0）
            create(context, file.absolutePath, Delegate.CPU).also { activeDelegate = Delegate.CPU }
        }
        embedder = created
        return created
    }

    private fun create(context: Context, path: String, delegate: Delegate): UniversalEmbedder {
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(path)
                    .setDelegate(delegate)
                    .build()
            )
            .setTextDelegate(delegate)
            .setVisionDelegate(delegate)
            .setAudioDelegate(Delegate.CPU)
            .setL2Normalize(true)
            .build()
        return UniversalEmbedder.createFromOptions(context, options)
    }

    fun activeDelegateName(): String = activeDelegate.name

    /** 检索查询编码（自动加 SearchQuery 前缀，HANDOFF §2） */
    fun embedQuery(context: Context, query: String): FloatArray =
        ensure(context).embedText(Prompts.searchQuery(query))
            .embeddings()[0].floatEmbedding()

    /** 图片编码（视觉塔） */
    fun embedImage(context: Context, bitmap: Bitmap): FloatArray =
        ensure(context).embedImage(BitmapImageBuilder(bitmap).build() as MPImage)
            .embeddings()[0].floatEmbedding()

    /** 原始文本编码（入库文档用，加 Document 前缀由调用方控制） */
    fun embedText(context: Context, text: String): FloatArray =
        ensure(context).embedText(text).embeddings()[0].floatEmbedding()

    @Synchronized
    fun release() {
        embedder?.close()
        embedder = null
    }
}
