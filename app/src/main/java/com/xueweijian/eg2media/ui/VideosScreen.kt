package com.xueweijian.eg2media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xueweijian.eg2media.media.MediaVideo
import com.xueweijian.eg2media.ui.videos.VideoResult
import com.xueweijian.eg2media.ui.videos.VideoViewModel
import java.util.Locale

/**
 * 视频模态页（v0.24 双形态，对齐图片栏体验）：
 * - 无查询 = 全部生效视频网格（缩略图+时长+点击播放）
 * - 有查询 = 时间段命中卡（HitMerge 设计保留——领先官方的差异化能力）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(vm: VideoViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var previewItem by remember { mutableStateOf<VideoResult?>(null) }
    var previewAll by remember { mutableStateOf<MediaVideo?>(null) }

    LaunchedEffect(Unit) { vm.refreshAllVideos() }

    previewItem?.let { p ->
        MediaPreviewDialog(
            uriString = p.uri,
            isVideo = true,
            startMs = p.startMs,
            onDismiss = { previewItem = null },
        )
    }
    previewAll?.let { p ->
        MediaPreviewDialog(
            uriString = p.uri.toString(),
            isVideo = true,
            startMs = 0L,
            onDismiss = { previewAll = null },
        )
    }

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
                "视频索引排队中（电量充足即自动开始）",
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
            trailingIcon = {
                when {
                    ui.loading -> Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    ui.query.isNotEmpty() -> androidx.compose.material3.IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
        )

        if (ui.query.isBlank()) {
            // v0.24：全部视频网格（图片栏同款骨架：weight(1f) 有界高度）
            if (ui.allLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "全部视频 · ${ui.allVideos.size} 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(ui.allVideos, key = { it.scopeKey }) { v ->
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { previewAll = v },
                    ) {
                        MediaThumb(v.uri.toString(), Modifier.fillMaxSize())
                        Icon(
                            Icons.Filled.PlayCircle,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(34.dp),
                        )
                        if (v.durationMs > 0) {
                            Text(
                                fmt(v.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(
                                        Color(0x88000000),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // grid.items 重载冲突：lazy 版用 count 形式（LazyListScope 成员，免 import）
                items(
                    count = ui.results.size,
                    key = { i -> "${ui.results[i].uri}-${ui.results[i].startMs}" },
                ) { i ->
                    val r = ui.results[i]
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        onClick = { previewItem = r },
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box {
                                MediaThumb(r.uri, Modifier.size(84.dp))
                                Icon(
                                    Icons.Filled.PlayCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    }
}

internal fun fmt(ms: Long): String {
    val totalSec = ms / 1000
    return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
}
