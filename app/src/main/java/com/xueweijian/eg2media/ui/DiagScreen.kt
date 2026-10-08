package com.xueweijian.eg2media.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xueweijian.eg2media.ui.diag.DiagViewModel

/** 真机自检面板（debug 入口）：形状断言 + golden 回归 + 语义判别断言 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(onDismiss: () -> Unit, vm: DiagViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportReport(uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自检诊断") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.run() }, enabled = !ui.running) {
                        Text(if (ui.report == null) "运行诊断" else "重新运行")
                    }
                    OutlinedButton(onClick = { confirmReset = true }, enabled = !ui.running) {
                        Text("重置基线")
                    }
                }
                if (ui.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(ui.step, style = MaterialTheme.typography.labelSmall)
                }
                ui.error?.let {
                    Text("出错：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(ui.error ?: ""))
                    }) { Text("复制错误信息") }
                }

                ui.report?.let { r ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (r.overallPass) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                        )
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (r.overallPass) "PASS ✓" else "FAIL ✗",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "${r.deviceModel} · ${r.socModel} · Android ${r.androidVersion} · ${r.abi}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "${r.modelFileName} · ${r.modelSizeBytes / 1_000_000}MB · ${r.delegate} · 加载 ${r.loadMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "sha256: ${r.modelSha256Prefix ?: "?"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (r.goldenBaseline) {
                                Text(
                                    "（首次运行：已采集 golden 基线，再跑一次以验证回归）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                    // 库统计（v0.21）：向量库里有什么，一眼看清（总数/模态分布/样本）
                    r.storeStats?.let { s ->
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("向量库：${s.total} 条", style = MaterialTheme.typography.titleSmall)
                                s.byModality.forEach { (mod, n) ->
                                    Text(
                                        "· $mod: $n 条  样本 ${s.samples[mod]?.joinToString(" | ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (s.total == 0) Text(
                                    "（库为空——索引未运行或已被清空）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                    r.items.forEach { item ->
                        Text(
                            "${item.key} [${item.kind}] dims=${item.dims} norm=%.4f cold=${item.coldMs}ms warm=${item.warmMs}ms golden=%s %s"
                                .format(
                                    item.norm,
                                    item.goldenCos?.let { "%.4f".format(it) } ?: "基线",
                                    if (item.pass) "✓" else "✗",
                                ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "判别：同义对 %.3f vs 无关对 %.3f %s"
                            .format(r.pairCos, r.unrelatedCos, if (r.discriminationPass) "✓" else "✗"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    r.failures.forEach { f ->
                        Text("· $f", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = {
                        exportLauncher.launch("eg2-diag-${r.deviceModel}-${System.currentTimeMillis()}.json")
                    }) { Text("导出 JSON 报告") }
                    ui.exportedTo?.let { Text("已导出 ✓", style = MaterialTheme.typography.labelSmall) }
                }

                // v0.21：清库重建（库被历史版本污染时的自救入口，官方 deleteAllRecords + forceRestart 同思路）
                OutlinedButton(
                    onClick = { confirmRebuild = true },
                    enabled = !ui.running,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("清空向量库并重建索引") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置 golden 基线？") },
            text = { Text("仅换模型版本或明确预期变化时使用。当前基线将被删除，下次运行重新采集。") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.run(resetGolden = true) }) { Text("重置并运行") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("清空向量库并重建？") },
            text = {
                Text(
                    "删除全部已索引的向量记录（照片/视频/文档不受影响），随后自动重新索引。全部媒体重新编码需要一些时间。\n\n适用：搜索结果与内容明显不符、库被旧版本数据污染。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    // 清库 → 取消在跑的索引 → 重新调度（scheduleIndexing 会因无活跃任务而入队）
                    scope.launch {
                        runCatching {
                            com.xueweijian.eg2media.store.RetrievalStore.get(context).deleteAll()
                            val wm = androidx.work.WorkManager.getInstance(context)
                            wm.cancelUniqueWork(com.xueweijian.eg2media.index.ImageIndexWorker.UNIQUE_NAME)
                            wm.cancelUniqueWork(com.xueweijian.eg2media.index.VideoIndexWorker.UNIQUE_NAME)
                            scheduleIndexing(context)
                        }
                    }
                }) { Text("清空并重建") }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("取消") } },
        )
    }
}
