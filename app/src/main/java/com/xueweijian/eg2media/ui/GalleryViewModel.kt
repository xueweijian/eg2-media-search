package com.xueweijian.eg2media.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueweijian.eg2media.core.Modality
import com.xueweijian.eg2media.media.CustomAssetStore
import com.xueweijian.eg2media.media.MediaStoreRepo
import com.xueweijian.eg2media.store.RetrievalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryItem(
    val uri: String,
    val isVideo: Boolean,
    val dateMs: Long,
    val durationMs: Long,
    val displayName: String,
    val scopeKey: String = "",
)

/**
 * 图库页（v0.24 升级：官方"管理"菜单三层资产语义）：
 * 生效集 = 可见 ∪ 自定义 − 移除；追加走 photo picker 持久授权；
 * 移除同步清向量库（deleteBySource）；恢复 = 清移除集 + 差集重索引。
 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    /** 被移除资产数（"恢复已移除 N 项"菜单条件显示） */
    private val _removedCount = MutableStateFlow(0)
    val removedCount: StateFlow<Int> = _removedCount

    fun refresh(force: Boolean = false) {
        if (!force && _items.value.isNotEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) {
                val imgs = MediaStoreRepo.effectiveImages(ctx).map {
                    GalleryItem(
                        uri = it.uri.toString(),
                        isVideo = false,
                        dateMs = it.dateModifiedMs,
                        durationMs = 0,
                        displayName = "",
                        scopeKey = it.scopeKey,
                    )
                }
                val vids = MediaStoreRepo.effectiveVideos(ctx).map {
                    GalleryItem(
                        uri = it.uri.toString(),
                        isVideo = true,
                        dateMs = it.dateModifiedMs,
                        durationMs = it.durationMs,
                        displayName = it.displayName,
                        scopeKey = it.scopeKey,
                    )
                }
                (imgs + vids).sortedByDescending { it.dateMs }
            }
            _items.value = list
            _removedCount.value = withContext(Dispatchers.IO) {
                CustomAssetStore.removedKeys(ctx).size
            }
            _loading.value = false
        }
    }

    /** 追加素材（官方"添加更多照片"）：持久授权 + prefs + 差集重索引 */
    fun addCustom(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CustomAssetStore.addCustomUris(ctx, uris) }
            refreshAfterAssetChange(ctx)
        }
    }

    fun startSelection() {
        _selecting.value = true
        _selected.value = emptySet()
    }

    fun cancelSelection() {
        _selecting.value = false
        _selected.value = emptySet()
    }

    fun toggleSelect(scopeKey: String) {
        _selected.value = _selected.value.let { if (scopeKey in it) it - scopeKey else it + scopeKey }
    }

    /** 移除所选：标 removed + 清向量库（图片/视频各自模态）+ 刷新 */
    fun removeSelected() {
        val keys = _selected.value
        if (keys.isEmpty()) return
        val ctx = getApplication<Application>()
        val targets = _items.value.filter { it.scopeKey in keys }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                CustomAssetStore.markRemoved(ctx, keys)
                val store = RetrievalStore.get(ctx)
                for (t in targets) {
                    runCatching {
                        store.deleteBySource(
                            t.uri,
                            if (t.isVideo) Modality.VIDEO_FRAME else Modality.IMAGE,
                        )
                    }
                }
            }
            cancelSelection()
            refreshAfterAssetChange(ctx)
        }
    }

    /** 恢复全部已移除（官方"添加全部 N 张"对应位） */
    fun restoreRemoved() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CustomAssetStore.clearRemoved(ctx) }
            refreshAfterAssetChange(ctx)
        }
    }

    /** 移除所有：清 prefs + 清向量库 */
    fun removeAll() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                CustomAssetStore.markAllRemoved(ctx)
                runCatching { RetrievalStore.get(ctx).deleteAll() }
            }
            refreshAfterAssetChange(ctx)
        }
    }

    private fun refreshAfterAssetChange(ctx: Context) {
        _items.value = emptyList() // 强制重查
        refresh(force = true)
        viewModelScope.launch {
            // 差集幂等：只索引新增/恢复的，已有记录零成本秒过
            runCatching { scheduleIndexing(ctx) }
        }
    }
}
