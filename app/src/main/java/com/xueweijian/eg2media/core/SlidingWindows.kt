package com.xueweijian.eg2media.core

data class Window(val index: Int, val startMs: Long, val endMs: Long)

/**
 * 长媒体滑窗切分（HANDOFF §4）。
 * 音频默认 90s 窗 / 45s 步（规范区间 60-120s 窗、30-60s 步的中位）；
 * 窗口越小定位越准、向量越多——这是"5.5min 单次上限"的工程化对策。
 */
object SlidingWindows {

    fun cut(
        durationMs: Long,
        windowMs: Long = 90_000,
        stepMs: Long = 45_000,
        minTailMs: Long = 15_000,
    ): List<Window> {
        require(windowMs > 0 && stepMs > 0) { "windowMs/stepMs must be positive" }
        if (durationMs <= 0) return emptyList()
        if (durationMs <= windowMs) return listOf(Window(0, 0L, durationMs))

        val wins = mutableListOf<Window>()
        var start = 0L
        while (true) {
            val end = minOf(start + windowMs, durationMs)
            wins.add(Window(wins.size, start, end))
            if (end >= durationMs) break
            start += stepMs
        }
        // 末窗时长过短则并入前一窗（避免碎片向量）
        if (wins.size >= 2) {
            val last = wins.last()
            if (last.endMs - last.startMs < minTailMs) {
                wins.removeAt(wins.lastIndex)
                val prev = wins.removeAt(wins.lastIndex)
                wins.add(prev.copy(endMs = durationMs))
            }
        }
        return wins
    }
}
