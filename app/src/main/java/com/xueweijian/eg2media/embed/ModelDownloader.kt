package com.xueweijian.eg2media.embed

import android.content.Context
import com.xueweijian.eg2media.core.ModelSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型下载器（HANDOFF §2.5）。
 * - 源序：自托管(若配) → hf-mirror → huggingface.co，逐源失败降级
 * - 断点续传：.part 临时文件 + Range
 * - 完成后原子重命名；损坏检测 = 长度 + litert magic 占位（正式版补 sha256 表）
 */
class ModelDownloader(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Downloading(val bytes: Long, val totalBytes: Long, val sourceHost: String) : State
        data class Done(val file: File) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun modelDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun localFile(fileName: String): File = File(modelDir(), fileName)

    suspend fun download(fileName: String): File = withContext(Dispatchers.IO) {
        val target = localFile(fileName)
        if (target.exists() && target.length() > 0) {
            _state.value = State.Done(target)
            return@withContext target
        }
        val part = File(target.absolutePath + ".part")
        val urls = ModelSources.candidateUrls(fileName)
        var lastError: Exception? = null
        for (url in urls) {
            try {
                downloadFrom(URL(url), part, target)
                _state.value = State.Done(target)
                return@withContext target
            } catch (e: Exception) {
                lastError = e
                _state.value = State.Failed(e.message ?: "download failed")
            }
        }
        throw IOException("all sources failed for $fileName: ${lastError?.message}")
    }

    private fun downloadFrom(url: URL, part: File, target: File) {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        val have = part.length()
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val code = conn.responseCode
        if (code !in 200..299 && code != 416) throw IOException("HTTP $code for $url")
        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong + (if (code == 206) have else 0) else -1L
        val append = code == 206
        val out = if (append) java.io.FileOutputStream(part, true) else java.io.FileOutputStream(part)
        conn.inputStream.use { input ->
            out.use { o ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } >= 0) {
                    o.write(buf, 0, read)
                    val cur = part.length()
                    _state.value = State.Downloading(cur, total, url.host)
                }
            }
        }
        if (part.length() <= 0) throw IOException("empty body from $url")
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        conn.disconnect()
    }
}
