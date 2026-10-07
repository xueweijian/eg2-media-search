package com.xueweijian.eg2media.ui.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueweijian.eg2media.core.HitMerge
import com.xueweijian.eg2media.core.Mrl
import com.xueweijian.eg2media.embed.EmbedderManager
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
                val q512 = Mrl.truncateAndRenormalize(qVec, RetrievalStore.DEFAULT_DIMS)
                val s = store ?: RetrievalStore(ctx).also { store = it }
                val hits = s.search(q512, topK = 24)
                val merged = HitMerge.merge(hits)
                merged.mapNotNull { h ->
                    val meta = s.getMeta(listOf(h.recordId)).firstOrNull()
                    val uri = meta?.get("uri")
                    if (uri.isNullOrBlank()) null
                    else SearchResult(uri, h.score, h.modality.code)
                }
            }.getOrElse { e ->
                _state.value = _state.value.copy(error = e.message)
                emptyList()
            }
        }

    override fun onCleared() {
        store?.close()
        EmbedderManager.release()
        super.onCleared()
    }
}
