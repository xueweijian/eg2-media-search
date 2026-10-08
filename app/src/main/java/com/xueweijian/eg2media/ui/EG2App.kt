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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
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
    TabSpec("图片"), TabSpec("视频"), TabSpec("音频"), TabSpec("文档"),
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
            when (tab) {
                0 -> SearchHome()
                1 -> VideosScreen()
                3 -> DocsScreen()
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
        // 媒体权限才决定主流程；通知权限被拒只影响前台通知可见性，不阻塞索引
        hasPermission = grants
            .filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
            .values.all { it }
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
                            Manifest.permission.POST_NOTIFICATIONS,
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

        // 无查询 = Google 相册形态（照片流常驻）；有查询 = 结果网格
        // v0.19：两个网格都对齐官方骨架（LazyVerticalGrid 必须 weight(1f) 有界高度，
        // 此前无界嵌套 = outBeyondBounds 测量崩溃闪退）
        if (ui.query.isBlank()) {
            if (hasPermission && setup.modelReady) {
                GalleryGridInline(Modifier.weight(1f))
            }
        } else {
            if (hasPermission && setup.modelReady &&
                ui.results.isEmpty() && !ui.loading
            ) {
                Text(
                    if (ui.error != null) "出错：${ui.error}" else "没有命中——换个说法试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            var previewUri by remember { mutableStateOf<String?>(null) }
            previewUri?.let { u ->
                MediaPreviewDialog(
                    uriString = u,
                    isVideo = false,
                    onDismiss = { previewUri = null },
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(ui.results, key = { it.uri }) { r ->
                    ResultCard(r.uri, r.score) { previewUri = r.uri }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ResultCard(uriString: String, score: Double, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
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

private suspend fun scheduleIndexing(context: android.content.Context) {
    val wm = WorkManager.getInstance(context)
    // 开箱即索：仅要求非低电（充电约束导致首装用户"排队中"永远不跑——真机实测教训）
    val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    // v0.19（对齐 Edge Gallery SmartAlbumIndexingWorker.enqueue）：
    // - 默认 KEEP：正在跑/排队中绝不掀桌子（REPLACE 曾把每次进 tab 变成"从头索引"）
    // - 已 SUCCEEDED 的 unique work 再次 enqueue 会被 WorkManager 当作新任务重新入队，
    //   所以这里显式短路——无活跃任务才入队（空差集秒退无通知，差集由 observer 增量补）
    suspend fun shouldEnqueue(uniqueName: String): Boolean {
        val infos = runCatching {
            wm.getWorkInfosForUniqueWork(uniqueName).await()
        }.getOrNull() ?: return true
        return infos.none {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
    }

    if (shouldEnqueue(ImageIndexWorker.UNIQUE_NAME)) {
        wm.enqueueUniqueWork(
            ImageIndexWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImageIndexWorker>().setConstraints(constraints).build(),
        )
    }
    if (shouldEnqueue(com.xueweijian.eg2media.index.VideoIndexWorker.UNIQUE_NAME)) {
        wm.enqueueUniqueWork(
            com.xueweijian.eg2media.index.VideoIndexWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<com.xueweijian.eg2media.index.VideoIndexWorker>()
                .setConstraints(constraints)
                .build(),
        )
    }
}
