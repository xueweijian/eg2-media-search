package com.xueweijian.eg2media.ui.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueweijian.eg2media.core.HitMerge
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.embed.EmbedderManager
import com.xueweijian.eg2media.media.ImageLoader
import com.xueweijian.eg2media.store.RetrievalStore
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

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    /** 合并后的命中（含 uri 元数据） */
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
    val modelReady: Boolean = false,
    val delegate: String = "",
)

data class SearchResult(
    val uri: String,
    val score: Double,
    val modality: String,
)

/**
 * search-as-you-type（HANDOFF §6.3）：逐键重排。
 * 推理/检索全在 IO dispatcher，主线程零接触（§6.4 性能规范）。
 */
@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val queryInput = MutableStateFlow("")

    private var store: RetrievalStore? = null

    init {
        queryInput
            .debounce(120)
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .map { q -> q to doSearch(q) }
            .onEach { (q, res) ->
                _state.value = _state.value.copy(
                    query = q,
                    loading = false,
                    results = res,
                    error = null,
                )
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q, loading = q.isNotBlank())
        queryInput.value = q
        if (q.isBlank()) _state.value = _state.value.copy(results = emptyList(), loading = false)
    }

    fun refreshModelStatus() {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            modelReady = EmbedderManager.isModelReady(ctx),
        )
    }

    private suspend fun doSearch(q: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (!EmbedderManager.isModelReady(ctx)) return@withContext emptyList()
            runCatching {
                val qVec = EmbedderManager.embedQuery(ctx, q)
                searchByVector(qVec, excludeUri = null)
            }.getOrElse { e ->
                _state.value = _state.value.copy(error = e.message)
                emptyList()
            }
        }

    /** 图搜图：PhotoPicker 选图 → 视觉塔 → 最近邻（排除自身） */
    fun searchByImage(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val res = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                if (!EmbedderManager.isModelReady(ctx)) {
                    _state.value = _state.value.copy(loading = false, error = "索引引擎未就绪")
                    return@withContext emptyList()
                }
                runCatching {
                    val bmp = ImageLoader.decode(ctx, uri, targetEdge = 768)
                        ?: error("无法读取所选图片")
                    val vec = try {
                        EmbedderManager.embedImage(ctx, bmp)
                    } finally {
                        bmp.recycle()
                    }
                    searchByVector(vec, excludeUri = uri.toString())
                }.getOrElse { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message)
                    emptyList()
                }
            }
            _state.value = _state.value.copy(loading = false, results = res)
        }
    }

    /** 768d 向量 → 512d 截断 → top-k → 合并 → 带 uri 的结果 */
    private suspend fun searchByVector(
        vec768: FloatArray,
        excludeUri: String?,
    ): List<SearchResult> {
        val ctx = getApplication<Application>()
        val q512 = Mrl.truncateAndRenormalize(vec768, RetrievalStore.DEFAULT_DIMS)
        val s = store ?: RetrievalStore(ctx).also { store = it }
        val hits = s.search(q512, topK = 24)
        val merged = HitMerge.merge(hits)
        return merged.mapNotNull { h ->
            val meta = s.getMeta(listOf(h.recordId)).firstOrNull()
            val uri = meta?.get("uri")
            when {
                uri.isNullOrBlank() -> null
                uri == excludeUri -> null // 图搜图排除自身
                else -> SearchResult(uri, h.score, h.modality.code)
            }
        }
    }

    override fun onCleared() {
        store?.close()
        EmbedderManager.release()
        super.onCleared()
    }
}
