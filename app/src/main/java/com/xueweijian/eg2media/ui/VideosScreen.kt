package com.xueweijian.eg2media.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xueweijian.eg2media.ui.videos.VideoViewModel
import java.util.Locale

/** 视频模态页：抽帧索引进度 + 文搜视频时间段 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(vm: VideoViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("视频检索 · 抽帧索引 · 时间段定位", style = MaterialTheme.typography.titleMedium)

        when {
            ui.index.running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (ui.index.frameTotal > 0)
                        "索引视频 ${ui.index.videoDone}/${ui.index.videoTotal} · 帧 ${ui.index.frameDone}/${ui.index.frameTotal}"
                    else "索引视频 ${ui.index.videoDone}/${ui.index.videoTotal}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ui.index.enqueued -> Text(
                "视频索引排队中（接通电源后自动开始）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (ui.indexedVideos > 0) "已索引 ${ui.indexedVideos} 个视频" else "视频索引等待就绪",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.error?.let {
            Text("出错：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (ui.indexedVideos > 0) "搜视频画面…" else "索引完成后可搜") },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.results, key = { "${it.uri}-${it.startMs}" }) { r ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(intent)
                        }
                    },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${fmt(r.startMs)} - ${fmt(r.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "%.2f".format(r.score),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

internal fun fmt(ms: Long): String {
    val totalSec = ms / 1000
    return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
}
