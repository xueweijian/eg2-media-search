package com.xueweijian.eg2media.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xueweijian.eg2media.index.ImageIndexWorker
import com.xueweijian.eg2media.media.ImageLoader
import com.xueweijian.eg2media.ui.search.SearchViewModel
import com.xueweijian.eg2media.ui.theme.EG2MediaTheme
import com.xueweijian.eg2media.ui.theme.geminiGradient
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Bolt

private data class TabSpec(val label: String)

private val tabs = listOf(
    TabSpec("图片"), TabSpec("视频"), TabSpec("音频"), TabSpec("文档"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EG2App() {
    EG2MediaTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("EG2 Media Search", style = MaterialTheme.typography.titleLarge) })
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, spec ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {
                                Icon(
                                    when (i) {
                                        0 -> Icons.Filled.Image
                                        1 -> Icons.Filled.PlayCircle
                                        2 -> Icons.Filled.PlayArrow
                                        else -> Icons.Filled.Description
                                    },
                                    contentDescription = spec.label,
                                )
                            },
                            label = { Text(spec.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (tab == 0) SearchHome() else ComingSoon(tabs[tab].label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHome(vm: SearchViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(hasMediaPermission(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants.values.all { it }
        if (hasPermission) scheduleIndexing(context)
    }
    LaunchedEffect(Unit) { vm.refreshModelStatus() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索你的相册…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (ui.loading) {
                    Icon(Icons.Filled.Bolt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                }
            },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            enabled = ui.modelReady,
        )

        when {
            !hasPermission -> StatusCard(
                title = "第一步：授权相册",
                body = "完全本地运行，照片永不上传。授权后将在充电时自动建立语义索引。",
                action = "授予权限",
                onClick = {
                    val perms = if (Build.VERSION.SDK_INT >= 33) {
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                        )
                    } else {
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    launcher.launch(perms)
                },
            )

            !ui.modelReady -> StatusCard(
                title = "索引引擎待就绪",
                body = "EG2 740M 模型未就位（首次使用时从镜像自动下载；开发阶段可 adb push 到 app files/models/）。",
                action = "重试检测",
                onClick = { vm.refreshModelStatus() },
            )

            else -> if (ui.results.isEmpty() && ui.query.isNotBlank() && !ui.loading) {
                Text(
                    if (ui.error != null) "出错：${ui.error}" else "没有命中——索引可能还在建立，或换个说法试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.results, key = { it.uri }) { r ->
                ResultCard(r.uri, r.score)
            }
        }
    }
}

@Composable
private fun ResultCard(uriString: String, score: Double) {
    val context = LocalContext.current
    val bmp = remember(uriString) {
        runCatching {
            ImageLoader.decode(context, android.net.Uri.parse(uriString), targetEdge = 256)
        }.getOrNull()
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize()) // 解码失败占位
                }
            }
            Text(
                "%.2f".format(score),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, action: String, onClick: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) {
                androidx.compose.material3.FilledTonalButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

@Composable
private fun ComingSoon(label: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            "$label 检索",
            style = MaterialTheme.typography.headlineMedium.copy(
                brush = Brush.linearGradient(geminiGradient),
            ),
            textAlign = TextAlign.Center,
        )
        Text(
            "即将上线 · 全本地 · 零上传",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun hasMediaPermission(context: android.content.Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun scheduleIndexing(context: android.content.Context) {
    val req = OneTimeWorkRequestBuilder<ImageIndexWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        ImageIndexWorker.UNIQUE_NAME,
        ExistingWorkPolicy.KEEP,
        req,
    )
}
