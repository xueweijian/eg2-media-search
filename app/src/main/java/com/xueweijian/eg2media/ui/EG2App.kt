package com.xueweijian.eg2media.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.xueweijian.eg2media.ui.setup.SetupViewModel
import com.xueweijian.eg2media.ui.theme.EG2MediaTheme
import com.xueweijian.eg2media.ui.theme.geminiGradient
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Bolt

private data class TabSpec(val label: String)

private val tabs = listOf(
    TabSpec("图库"), TabSpec("图片"), TabSpec("视频"), TabSpec("音频"), TabSpec("文档"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EG2App() {
    EG2MediaTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showDiag by rememberSaveable { mutableStateOf(false) }
        // debug 构建（FLAG_DEBUGGABLE）才显示自检入口（方案 ①）
        val ctx = LocalContext.current
        val isDebug = remember {
            (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
        if (showDiag) DiagScreen(onDismiss = { showDiag = false })
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("EG2 Media Search", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        if (isDebug) {
                            IconButton(onClick = { showDiag = true }) {
                                Icon(
                                    Icons.Filled.Bolt,
                                    contentDescription = "自检诊断",
                                )
                            }
                        }
                    },
                )
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
                                        1 -> Icons.Filled.Search
                                        2 -> Icons.Filled.PlayCircle
                                        3 -> Icons.Filled.PlayArrow
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
            when (tab) {
                0 -> GalleryScreen()
                1 -> SearchHome()
                2 -> VideosScreen()
                4 -> DocsScreen()
                else -> ComingSoon(tabs[tab].label)
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHome(
    vm: SearchViewModel = viewModel(),
    setupVm: SetupViewModel = viewModel(),
) {
    val context = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val setup by setupVm.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(hasMediaPermission(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants.values.all { it }
    }
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.searchByImage(uri)
    }
    LaunchedEffect(Unit) { setupVm.refresh() }

    // 模型就绪 + 有权限 → 自动开始索引（幂等，KEEP 策略）
    LaunchedEffect(setup.modelReady, hasPermission) {
        if (setup.modelReady && hasPermission) scheduleIndexing(context)
    }

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
            placeholder = { Text(if (setup.modelReady) "搜索你的相册…" else "先准备索引引擎…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (ui.loading) {
                    Icon(Icons.Filled.Bolt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                }
            },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            enabled = setup.modelReady,
        )

        when {
            !hasPermission -> StatusCard(
                title = "第一步：授权相册",
                body = "完全本地运行，照片永不上传。授权后自动建立语义索引（无需充电）。",
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

            !setup.modelReady -> DownloadCard(setupVm = setupVm)

            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IndexProgressRow(setup.index)
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                ) {
                    Text("📷 以图搜图")
                }
            }
        }

        if (hasPermission && setup.modelReady &&
            ui.results.isEmpty() && ui.query.isNotBlank() && !ui.loading
        ) {
            Text(
                if (ui.error != null) "出错：${ui.error}" else "没有命中——索引可能还在建立，或换个说法试试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
@OptIn(ExperimentalMaterial3Api::class)
private fun ResultCard(uriString: String, score: Double) {
    val context = LocalContext.current
    Card(
        onClick = { openPreview(context, uriString, "image/*", "图片") },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MediaThumb(uriString, Modifier.size(96.dp))
            Text(
                "%.2f".format(score),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadCard(setupVm: SetupViewModel) {
    val setup by setupVm.state.collectAsStateWithLifecycle()
    val d = setup.download
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("第二步：准备索引引擎", style = MaterialTheme.typography.titleMedium)
            Text(
                "EG2 740M · 约 485MB · 一次性下载，之后全部离线。源：国内镜像优先，自动回退。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                setup.modelFile,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                d.running -> {
                    LinearProgressIndicator(
                        progress = {
                            if (d.totalBytes > 0) d.bytes.toFloat() / d.totalBytes else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${d.bytes / 1e6} / ${if (d.totalBytes > 0) "${d.totalBytes / 1e6} MB" else "?"} · ${d.host}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                d.failed != null -> {
                    Text(
                        "下载失败：${d.failed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FilledTonalButton(onClick = setupVm::startDownload) { Text("重试下载") }
                }

                else -> FilledTonalButton(onClick = setupVm::startDownload) { Text("下载模型") }
            }
        }
    }
}

@Composable
private fun IndexProgressRow(index: com.xueweijian.eg2media.ui.setup.IndexUi) {
    when {
        index.running -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = {
                        if (index.total > 0) index.done.toFloat() / index.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (index.total > 0) "建立索引 ${index.done}/${index.total}"
                    else "建立索引中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        index.enqueued -> Text(
            "索引排队中（电量充足即自动开始）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        index.failed -> Text(
            "索引失败——点右上 ⚡ 跑诊断定位问题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        index.finished -> Text(
            if (index.failedCount > 0)
                "索引就绪（${index.failedCount} 张失败已跳过）✓ 可以搜索"
            else "索引已就绪 ✓ 可以开始搜索",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
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
    val wm = WorkManager.getInstance(context)
    // 开箱即索：仅要求非低电（充电约束导致首装用户"排队中"永远不跑——真机实测教训）
    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()
    wm.enqueueUniqueWork(
        ImageIndexWorker.UNIQUE_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<ImageIndexWorker>().setConstraints(constraints).build(),
    )
    wm.enqueueUniqueWork(
        com.xueweijian.eg2media.index.VideoIndexWorker.UNIQUE_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<com.xueweijian.eg2media.index.VideoIndexWorker>()
            .setConstraints(constraints)
            .build(),
    )
}
