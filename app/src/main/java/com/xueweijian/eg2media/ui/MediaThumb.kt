package com.xueweijian.eg2media.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xueweijian.eg2media.media.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 缩略图加载：系统 loadThumbnail 优先（MediaProvider 缓存，云图/HEIC 都稳），失败回退手动下采样 */
object ThumbLoader {
    fun load(context: Context, uriString: String, edge: Int = 320): Bitmap? {
        val uri = Uri.parse(uriString)
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
        }.getOrElse {
            runCatching { ImageLoader.decode(context, uri, targetEdge = edge) }.getOrNull()
        }
    }
}

/** 结果卡片 / 图库通用的异步缩略图：IO 线程加载，加载中转圈，失败给占位图标（不再黑块静默） */
@Composable
fun MediaThumb(uriString: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bmp by remember(uriString) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uriString) { mutableStateOf(false) }
    LaunchedEffect(uriString) {
        val b = withContext(Dispatchers.IO) { ThumbLoader.load(context, uriString) }
        if (b != null) bmp = b else failed = true
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Icon(
                Icons.Filled.Image,
                contentDescription = "缩略图不可用",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp),
            )

            else -> LinearProgressIndicator(Modifier.size(20.dp))
        }
    }
}

/** 系统预览兜底（app 内预览失败时），无 handler 时 Toast 提示而非静默吞掉 */
fun openPreview(context: Context, uriString: String, mime: String, label: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uriString), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "没有应用能打开这个$label", Toast.LENGTH_SHORT).show()
    }
}

/** App 内全屏预览（用户需求：预览在本 app 完成）。图片全屏 Fit 显示；视频内置 VideoView 自动播放。
 *  解码失败给「用系统相册打开」出口。点击背景关闭。 */
@Composable
fun MediaPreviewDialog(
    uriString: String,
    isVideo: Boolean,
    startMs: Long = 0L,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            if (isVideo) {
                VideoPreview(uriString = uriString, startMs = startMs)
            } else {
                ImagePreview(uriString = uriString)
            }
            Text(
                "✕",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun ImagePreview(uriString: String) {
    val context = LocalContext.current
    var bmp by remember(uriString) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uriString) { mutableStateOf(false) }
    LaunchedEffect(uriString) {
        val b = withContext(Dispatchers.IO) { ThumbLoader.load(context, uriString, edge = 2048) }
        if (b != null) bmp = b else failed = true
    }
    val b = bmp
    when {
        b != null -> Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "全屏预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        failed -> Column(
            Modifier
                .align(Alignment.Center)
                .clickable {
                    openPreview(context, uriString, "image/*", "图片")
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "无法加载 · 点此用系统相册打开",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        else -> LinearProgressIndicator(
            Modifier
                .align(Alignment.Center)
                .size(36.dp),
        )
    }
}

@Composable
private fun VideoPreview(uriString: String, startMs: Long) {
    val context = LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            android.widget.VideoView(it).apply {
                setVideoURI(Uri.parse(uriString))
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    if (startMs > 0) seekTo(startMs.toInt())
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    openPreview(context, uriString, "video/*", "视频")
                    true
                }
            }
        },
        update = { it.requestFocus() },
        modifier = Modifier.fillMaxSize(),
    )
}
