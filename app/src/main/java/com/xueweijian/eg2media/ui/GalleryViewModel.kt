package com.xueweijian.eg2media.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueweijian.eg2media.media.MediaStoreRepo
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
)

/** 图库页：全部本地媒体（相册式浏览），部分授权时自然只显示系统可见项 */
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val _items = MutableStateFlow<List<GalleryItem>>(emptyList())
    val items: StateFlow<List<GalleryItem>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun refresh(force: Boolean = false) {
        if (!force && _items.value.isNotEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val list = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val imgs = MediaStoreRepo.queryImages(ctx).map {
                    GalleryItem(
                        uri = it.uri.toString(),
                        isVideo = false,
                        dateMs = it.dateModifiedMs,
                        durationMs = 0,
                        displayName = "",
                    )
                }
                val vids = MediaStoreRepo.queryVideos(ctx).map {
                    GalleryItem(
                        uri = it.uri.toString(),
                        isVideo = true,
                        dateMs = it.dateModifiedMs,
                        durationMs = it.durationMs,
                        displayName = it.displayName,
                    )
                }
                (imgs + vids).sortedByDescending { it.dateMs }
            }
            _items.value = list
            _loading.value = false
        }
    }
}
