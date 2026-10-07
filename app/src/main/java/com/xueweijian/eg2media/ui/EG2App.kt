package com.xueweijian.eg2media.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioTrack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xueweijian.eg2media.ui.theme.EG2MediaTheme
import com.xueweijian.eg2media.ui.theme.geminiGradient

private data class TabSpec(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabSpec("图片", Icons.Filled.Image),
    TabSpec("视频", Icons.Filled.PlayCircle),
    TabSpec("音频", Icons.Filled.AudioTrack),
    TabSpec("文档", Icons.Filled.Description),
)

@Composable
fun EG2App() {
    EG2MediaTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "EG2 Media Search",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, spec ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(spec.icon, contentDescription = spec.label) },
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

@Composable
private fun SearchHome() {
    var query by rememberSaveable { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(false) }

    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> hasPermission = grants.values.all { it } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索你的相册…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
        )

        if (!hasPermission) {
            StatusCard(
                title = "第一步：授权相册",
                body = "完全本地运行，照片永不上传。授权后开始夜间建立索引。",
                action = "授予权限",
                onClick = { launcher.launch(permissions) },
            )
        } else {
            StatusCard(
                title = "索引引擎待接入",
                body = "MediaPipe UniversalEmbedder 将在下一步接入（EG2 740M，485MB，首次使用时下载）。",
                action = null,
                onClick = null,
            )
        }

        // 结果网格占位（Round 2 接入真实检索）
        if (query.isNotEmpty()) {
            Text(
                "正在输入：$query\n（search-as-you-type 骨架，检索引擎未接入）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, action: String?, onClick: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null && onClick != null) {
                androidx.compose.material3.FilledTonalButton(onClick = onClick) {
                    Text(action)
                }
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
