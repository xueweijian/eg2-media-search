package com.xueweijian.eg2media.ui.videos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.index.VideoIndexWorker
import com.xueweijian.eg2media.media.MediaStoreRepo
import com.xueweijian.eg2media.search.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VideoResult(
    val uri: String,
    val fileName: String,
    val startMs: Long,
    val endMs: Long,
    val score: Double,
)

data class VideoIndexUi(
    val enqueued: Boolean = false,
    val running: Boolean = false,
    val finished: Boolean = false,
    val videoDone: Int = 0,
    val videoTotal: Int = 0,
    val frameDone: Int = 0,
    val frameTotal: Int = 0,
)

data class VideoUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<VideoResult> = emptyList(),
    val error: String? = null,
    val indexedVideos: Int = 0,
    val index: VideoIndexUi = VideoIndexUi(),
    /** v0.24：无查询时显示全部生效视频（对齐图片栏体验） */
    val allVideos: List<com.xueweijian.eg2media.media.MediaVideo> = emptyList(),
    val allLoading: Boolean = false,
)

/** 视频模态：抽帧索引进度 + 文搜视频（命中时间段） */
@OptIn(FlowPreview::class)
class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state

    private val queryInput = MutableStateFlow("")
    private var engine: SearchEngine? = null

    init {
        refreshCount()
        refreshAllVideos()
        queryInput
            .debounce(150)
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .map { q -> q to doSearch(q) }
            .onEach { (q, res) ->
                _state.value = _state.value.copy(query = q, loading = false, results = res)
            }
            .launchIn(viewModelScope)
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow(VideoIndexWorker.UNIQUE_NAME)
            .onEach { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                val finished = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                val data = active?.progress ?: finished?.outputData
                _state.value = _state.value.copy(
                    index = VideoIndexUi(
                        enqueued = active?.state == WorkInfo.State.ENQUEUED,
                        running = active?.state == WorkInfo.State.RUNNING,
                        finished = finished != null,
                        videoDone = data?.getInt(VideoIndexWorker.KEY_VDONE, 0) ?: 0,
                        videoTotal = data?.getInt(VideoIndexWorker.KEY_VTOTAL, 0) ?: 0,
                        frameDone = data?.getInt(VideoIndexWorker.KEY_FDONE, 0) ?: 0,
                        frameTotal = data?.getInt(VideoIndexWorker.KEY_FTOTAL, 0) ?: 0,
                    )
                )
                if (finished != null) refreshCount()
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q, loading = q.isNotBlank())
        queryInput.value = q
        if (q.isBlank()) _state.value = _state.value.copy(results = emptyList(), loading = false)
    }

    fun refreshCount() {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val e = engine ?: SearchEngine(ctx).also { engine = it }
            val n = e.indexedIds()
                .filter { it.contains("|${Modality.VIDEO_FRAME.code}|") }
                .map { it.substringBefore('|') }
                .distinct()
                .size
            _state.value = _state.value.copy(indexedVideos = n)
        }
    }

    /** v0.24：全部生效视频（管理菜单增删后 GalleryViewModel 会广播刷新——这里进 tab 时重查即可） */
    fun refreshAllVideos() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _state.value = _state.value.copy(allLoading = true)
            val vids = withContext(Dispatchers.IO) {
                // v0.24.1 保险带：scopeKey 去重（Round 26.5 duplicate key 教训）
                MediaStoreRepo.effectiveVideos(ctx).distinctBy { it.scopeKey }
            }
            _state.value = _state.value.copy(allVideos = vids, allLoading = false)
        }
    }

    private suspend fun doSearch(q: String): List<VideoResult> =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (!EmbedderManager.isModelReady(ctx)) return@withContext emptyList()
            runCatching {
                val vec = EmbedderManager.embedQuery(ctx, q)
                val e = engine ?: SearchEngine(ctx).also { engine = it }
                e.query(vec, topK = 24, modality = Modality.VIDEO_FRAME).map { mh ->
                    VideoResult(
                        // v0.21：uri 由 recordId 确定性构造（MediaStore），meta 仅兜底
                        uri = SearchEngine.resolveDisplayUri(mh.hit, mh.meta) ?: "",
                        fileName = mh.meta["fn"] ?: "(视频)",
                        startMs = mh.hit.startMs,
                        endMs = mh.hit.endMs,
                        score = mh.hit.score,
                    )
                }.filter { it.uri.isNotBlank() }
            }.getOrElse { e ->
                _state.value = _state.value.copy(error = e.message)
                emptyList()
            }
        }

    override fun onCleared() {
        engine?.close()
        EmbedderManager.release()
        super.onCleared()
    }
}
