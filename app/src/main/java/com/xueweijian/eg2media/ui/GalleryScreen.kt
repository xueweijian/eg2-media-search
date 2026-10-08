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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 图库网格（嵌入「图片」tab：搜索框下方、无查询时显示全部本地媒体）。
 * Google 相册形态：照片流常驻，点击 app 内全屏预览。
 */
@Composable
fun GalleryGridInline(vm: GalleryViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<GalleryItem?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    preview?.let { p ->
        MediaPreviewDialog(
            uriString = p.uri,
            isVideo = p.isVideo,
            onDismiss = { preview = null },
        )
    }

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
    }

    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

    if (!loading && items.isEmpty()) {
        Text(
            "相册为空（或授权的是部分照片）——去系统设置把本应用的照片权限改为「全部」可扩大范围",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.uri }) { g ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { preview = g },
            ) {
                MediaThumb(g.uri, Modifier.fillMaxSize())
                if (g.isVideo) {
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
        }
    }
}
