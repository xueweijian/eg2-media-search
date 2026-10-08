package com.xueweijian.eg2media.ui.diag

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueweijian.eg2media.diag.DiagnosticsRunner
import com.xueweijian.eg2media.embed.EmbedderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagUiState(
    val running: Boolean = false,
    val step: String = "",
    val report: DiagnosticsRunner.Report? = null,
    val error: String? = null,
    val exportedTo: String? = null,
)

/** 真机自检：跑诊断 / 重置 golden 基线 / 导出 JSON 报告 */
class DiagViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state

    fun run(resetGolden: Boolean = false) {
        if (_state.value.running) return
        val ctx = getApplication<Application>()
        _state.value = DiagUiState(running = true, step = "准备…")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { DiagnosticsRunner(ctx).run(onStep = { s -> _state.value = _state.value.copy(step = s) }, resetGolden = resetGolden) }
            }.fold(
                onSuccess = { r ->
                    _state.value = _state.value.copy(running = false, step = "", report = r, error = null)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(running = false, step = "", error = e.message)
                },
            )
        }
    }

    fun exportReport(uri: Uri) {
        val report = _state.value.report ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(report.toJson().toByteArray())
                } ?: error("无法写入 $uri")
            }.fold(
                onSuccess = { _state.value = _state.value.copy(exportedTo = uri.toString()) },
                onFailure = { e -> _state.value = _state.value.copy(error = e.message) },
            )
        }
    }

    override fun onCleared() {
        EmbedderManager.release()
        super.onCleared()
    }
}
