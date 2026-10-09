package com.xueweijian.eg2media.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * 图库网格（嵌入「图片」tab：搜索框下方、无查询时显示全部生效资产）。
 * v0.24：官方"管理"菜单四项（添加更多/恢复已移除/选择移除/移除所有）+ 长按多选。
 * v0.19 骨架保留：LazyVerticalGrid 必须 weight(1f) 有界高度（越界测量闪退教训）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGridInline(modifier: Modifier = Modifier, vm: GalleryViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val removedCount by vm.removedCount.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<GalleryItem?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    val pickMore = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> vm.addCustom(uris) }

    LaunchedEffect(Unit) { vm.refresh() }

    preview?.let { p ->
        MediaPreviewDialog(
            uriString = p.uri,
            isVideo = p.isVideo,
            onDismiss = { preview = null },
        )
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("移除所有照片？") },
            text = { Text("将从本应用移除全部素材并清空索引向量库（系统相册不受影响，可随时重新授权添加）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAll = false
                    vm.removeAll()
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("取消") } },
        )
    }

    Column(modifier) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "已选 ${selected.size} 项",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { vm.removeSelected() },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  移除", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { vm.cancelSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "全部媒体",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${items.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { vm.refresh(force = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "管理", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("添加更多照片 / 视频") },
                            onClick = {
                                showMenu = false
                                pickMore.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                    ),
                                )
                            },
                        )
                        if (removedCount > 0) {
                            DropdownMenuItem(
                                text = { Text("恢复已移除的 $removedCount 项") },
                                onClick = {
                                    showMenu = false
                                    vm.restoreRemoved()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("选择要移除的照片") },
                            onClick = {
                                showMenu = false
                                vm.startSelection()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移除所有照片", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                confirmRemoveAll = true
                            },
                        )
                    }
                }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (!loading && items.isEmpty()) {
            Text(
                "相册为空——点右上 ⋮「添加更多照片 / 视频」追加素材",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(items, key = { it.scopeKey.ifEmpty { it.uri } }) { g ->
                val isSel = g.scopeKey in selected
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = {
                                if (selecting) vm.toggleSelect(g.scopeKey) else preview = g
                            },
                            onLongClick = {
                                if (!selecting) vm.startSelection()
                                vm.toggleSelect(g.scopeKey)
                            },
                        ),
                ) {
                    MediaThumb(g.uri, Modifier.fillMaxSize())
                    if (selecting && isSel) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0x66005AC8)),
                        )
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "已选",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(30.dp),
                        )
                    } else if (!selecting && g.isVideo) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "视频",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(30.dp)
                                .background(Color(0x66000000), CircleShape)
                                .padding(3.dp),
                        )
                        if (g.durationMs > 0) {
                            Text(
                                fmt(g.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color(0x88000000), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            } // items lambda
        } // LazyVerticalGrid
    } // Column
}
