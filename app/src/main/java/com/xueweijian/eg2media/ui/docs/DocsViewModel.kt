package com.xueweijian.eg2media.ui.docs

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.doc.DocIndexer
import com.xueweijian.eg2media.embed.EmbedderManager
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

data class DocResult(
    val fileName: String,
    val uri: String,
    val score: Double,
    val chunkStart: Int,
    val chunkEnd: Int,
)

data class DocUiState(
    val adding: Boolean = false,
    val addProgress: String? = null,
    val error: String? = null,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<DocResult> = emptyList(),
    val indexedDocs: Int = 0,
    val lastAdded: String? = null,
)

/** 文档模态：SAF 添加 → OCR 索引 → 文搜文档（块级命中，相邻块合并） */
@OptIn(FlowPreview::class)
class DocsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DocUiState())
    val state: StateFlow<DocUiState> = _state

    private val queryInput = MutableStateFlow("")
    private var engine: SearchEngine? = null

    init {
        refreshCount()
        queryInput
            .debounce(150)
            .distinctUntilChanged()
            .filter { it.isNotBlank() }
            .map { q -> q to doSearch(q) }
            .onEach { (q, res) ->
                _state.value = _state.value.copy(
                    query = q, loading = false, results = res, error = null,
                )
            }
            .launchIn(viewModelScope)
    }

    /** SAF 选中文档后调用：持久读权限 + 索引（OCR） */
    fun addDocument(uri: Uri, displayName: String, mimeType: String?) {
        val ctx = getApplication<Application>()
        // 持久化读权限（重开时仍可读）
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (_state.value.adding) return
        _state.value = _state.value.copy(
            adding = true, addProgress = "准备提取…", error = null, lastAdded = null,
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    DocIndexer(ctx).index(
                        uri = uri,
                        displayName = displayName,
                        mimeType = mimeType,
                        onPage = { c, t ->
                            _state.value = _state.value.copy(addProgress = "识别第 $c/$t 页…")
                        },
                        onChunk = { c, t ->
                            _state.value = _state.value.copy(addProgress = "索引块 $c/$t…")
                        },
                    )
                }
            }.fold(
                onSuccess = { r ->
                    _state.value = _state.value.copy(
                        adding = false, addProgress = null, lastAdded = displayName,
                    )
                    refreshCount()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        adding = false, addProgress = null, error = e.message,
                    )
                },
            )
        }
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
            val docs = e.indexedIds()
                .filter { it.contains("|${Modality.DOC_CHUNK.code}|") }
                .map { it.substringBefore('|') }
                .distinct()
                .size
            _state.value = _state.value.copy(indexedDocs = docs)
        }
    }

    private suspend fun doSearch(q: String): List<DocResult> =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (!EmbedderManager.isModelReady(ctx)) return@withContext emptyList()
            runCatching {
                val vec = EmbedderManager.embedQuery(ctx, q)
                val e = engine ?: SearchEngine(ctx).also { engine = it }
                e.query(vec, topK = 24, modality = Modality.DOC_CHUNK).map { mh ->
                    DocResult(
                        fileName = mh.meta["fn"] ?: "(未命名)",
                        uri = mh.meta["uri"] ?: mh.hit.sourceId,
                        score = mh.hit.score,
                        chunkStart = mh.hit.startMs.toInt(),
                        chunkEnd = mh.hit.endMs.toInt(),
                    )
                }
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
