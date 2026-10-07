package com.xueweijian.eg2media.core

/**
 * 视频抽帧网格：固定步长生成 [t, t+step) 区间序列。
 * 索引时每帧一个 VIDEO_FRAME 记录，相邻帧在检索侧被 HitMerge 合并成片段。
 * 超长视频自动放大步长，帧数封顶（索引时长护栏）。
 */
object FrameGrid {

    fun frames(
        durationMs: Long,
        stepMs: Long = 4_000,
        maxFrames: Int = 240,
    ): List<Window> {
        require(stepMs > 0 && maxFrames > 0)
        if (durationMs <= 0) return emptyList()
        var step = stepMs
        while ((durationMs + step - 1) / step > maxFrames) {
            step *= 2
        }
        val out = mutableListOf<Window>()
        var t = 0L
        var idx = 0
        while (t < durationMs) {
            val end = minOf(t + step, durationMs)
            out.add(Window(idx++, t, end))
            t = end
        }
        return out
    }
}
