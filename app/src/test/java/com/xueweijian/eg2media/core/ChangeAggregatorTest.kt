package com.xueweijian.eg2media.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/** 变更聚合行为契约：去重、窗口合并、多批、乱序线程安全 */
class ChangeAggregatorTest {

    private fun newScope() = CoroutineScope(Dispatchers.Default)

    @Test
    fun `窗口内多次 offer 合并成一批`() = runBlocking {
        val batches = Collections.synchronizedList(mutableListOf<List<Long>>())
        val scope = newScope()
        val agg = ChangeAggregator<Long>(windowMs = 80, scope = scope) { batch ->
            batches.add(batch)
        }
        agg.offer(1)
        agg.offer(2)
        agg.offer(3)
        withTimeout(3_000) { agg.awaitIdle() }
        // 等窗口真正结束
        kotlinx.coroutines.delay(200)
        assertEquals(listOf(listOf(1L, 2L, 3L)), batches.toList())
        scope.cancel()
    }

    @Test
    fun `重复 offer 去重`() = runBlocking {
        val batches = Collections.synchronizedList(mutableListOf<List<Long>>())
        val scope = newScope()
        val agg = ChangeAggregator<Long>(windowMs = 60, scope = scope) { batches.add(it) }
        agg.offer(42)
        agg.offer(42)
        agg.offer(42)
        kotlinx.coroutines.delay(300)
        assertEquals(listOf(listOf(42L)), batches.toList())
        scope.cancel()
    }

    @Test
    fun `跨窗口分批`() = runBlocking {
        val batches = Collections.synchronizedList(mutableListOf<List<String>>())
        val scope = newScope()
        val agg = ChangeAggregator<String>(windowMs = 60, scope = scope) { batches.add(it) }
        agg.offer("a")
        kotlinx.coroutines.delay(250) // 第一窗关闭
        agg.offer("b")
        kotlinx.coroutines.delay(250) // 第二窗关闭
        assertEquals(2, batches.size)
        assertEquals(listOf("a"), batches[0])
        assertEquals(listOf("b"), batches[1])
        scope.cancel()
    }

    @Test
    fun `flush 抛异常不丢后续批`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<List<Int>>())
        var first = true
        val scope = newScope()
        val agg = ChangeAggregator<Int>(windowMs = 50, scope = scope) { b ->
            seen.add(b)
            if (first) {
                first = false
                throw IllegalStateException("boom")
            }
        }
        agg.offer(1)
        kotlinx.coroutines.delay(200)
        agg.offer(2)
        kotlinx.coroutines.delay(200)
        assertEquals(listOf(listOf(1), listOf(2)), seen.toList())
        scope.cancel()
    }
}
