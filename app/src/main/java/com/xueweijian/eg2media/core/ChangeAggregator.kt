package com.xueweijian.eg2media.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedHashSet

/**
 * 变更聚合器：ContentObserver 的 onChange 风暴 → 去重 → 时间窗合并 → 批量 flush。
 * 照片连拍/批量删除会产生每秒数十次 onChange，逐条响应浪费且抖动；
 * 窗口内合并成一批，恰好对接"批量索引"的吞吐优势。
 */
class ChangeAggregator<T>(
    private val windowMs: Long = 1_000,
    private val scope: CoroutineScope,
    private val flush: suspend (List<T>) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashSet<T>()

    @Volatile
    private var drainJob: Job? = null

    fun offer(item: T) {
        synchronized(lock) {
            pending.add(item)
            if (drainJob?.isActive != true) {
                drainJob = scope.launch {
                    delay(windowMs)
                    val batch = synchronized(lock) {
                        if (pending.isEmpty()) return@launch
                        pending.toList().also { pending.clear() }
                    }
                    runCatching { flush(batch) }
                }
            }
        }
    }

    suspend fun awaitIdle() {
        drainJob?.join()
    }
}
