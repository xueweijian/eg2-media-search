package com.xueweijian.eg2media.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import com.xueweijian.eg2media.ui.docs.DocsViewModel

/** 文档模态页：添加（SAF）→ OCR 索引 → 文搜文档 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen(vm: DocsViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "文档"
            val mime = context.contentResolver.getType(uri)
            vm.addDocument(uri, name, mime)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "文档检索 · 本地 OCR · 零上传",
            style = MaterialTheme.typography.titleMedium,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    openDoc.launch(arrayOf("application/pdf", "text/*"))
                },
                enabled = !ui.adding,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" 添加文档")
            }
            Text(
                if (ui.indexedDocs > 0) "已索引 ${ui.indexedDocs} 份" else "尚无文档",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (ui.adding) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                ui.addProgress ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.lastAdded?.let {
            Text(
                "已索引：$it ✓",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ui.error?.let {
            Text(
                "出错：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (ui.indexedDocs > 0) "搜文档内容…" else "先添加一份文档") },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.results, key = { "${it.uri}-${it.chunkStart}" }) { r ->
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
                            if (r.chunkStart == r.chunkEnd) "第 ${r.chunkStart + 1} 块"
                            else "第 ${r.chunkStart + 1}-${r.chunkEnd + 1} 块（连续命中）",
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
