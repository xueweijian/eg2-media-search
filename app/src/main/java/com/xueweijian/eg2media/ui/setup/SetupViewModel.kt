package com.xueweijian.eg2media.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.embed.ModelDownloader
import com.xueweijian.eg2media.index.ImageIndexWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class DownloadUi(
    val running: Boolean = false,
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val host: String = "",
    val failed: String? = null,
    val done: Boolean = false,
)

data class IndexUi(
    val enqueued: Boolean = false,
    val running: Boolean = false,
    val finished: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

data class SetupUiState(
    val modelReady: Boolean = false,
    val modelFile: String = "",
    val download: DownloadUi = DownloadUi(),
    val index: IndexUi = IndexUi(),
)

/**
 * 环境准备面板：模型下载 + 索引进度。
 * 索引进度来自 WorkManager WorkInfo（ImageIndexWorker.setProgress），不侵入 worker。
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state

    private val downloader = ModelDownloader(app)

    init {
        refresh()
        // 下载进度
        downloader.state
            .onEach { s ->
                _state.value = _state.value.copy(
                    download = when (s) {
                        is ModelDownloader.State.Idle -> DownloadUi()
                        is ModelDownloader.State.Downloading -> DownloadUi(
                            running = true,
                            bytes = s.bytes,
                            totalBytes = s.totalBytes,
                            host = s.sourceHost,
                        )
                        is ModelDownloader.State.Done -> DownloadUi(done = true),
                        is ModelDownloader.State.Failed -> DownloadUi(failed = s.reason),
                    }
                )
            }
            .launchIn(viewModelScope)
        // 索引进度
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow(ImageIndexWorker.UNIQUE_NAME)
            .onEach { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                val finished = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                val prog = active?.progress ?: finished?.outputData
                val done = prog?.getInt(ImageIndexWorker.KEY_DONE, 0) ?: 0
                val total = prog?.getInt(ImageIndexWorker.KEY_TOTAL, 0) ?: 0
                _state.value = _state.value.copy(
                    index = IndexUi(
                        enqueued = active?.state == WorkInfo.State.ENQUEUED,
                        running = active?.state == WorkInfo.State.RUNNING,
                        finished = finished != null,
                        done = done,
                        total = total,
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            modelReady = EmbedderManager.isModelReady(ctx),
            modelFile = EmbedderManager.route(ctx).eg2File,
        )
    }

    fun startDownload() {
        val ctx = getApplication<Application>()
        if (_state.value.download.running) return
        viewModelScope.launch {
            runCatching { downloader.download(EmbedderManager.route(ctx).eg2File) }
                .onSuccess { refresh() }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        download = DownloadUi(failed = e.message ?: "download failed")
                    )
                }
        }
    }

    override fun onCleared() {
        EmbedderManager.release()
        super.onCleared()
    }
}
