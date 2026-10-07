package com.xueweijian.eg2media.core

data class Hit(
    val recordId: String,
    val sourceId: String,
    val modality: Modality,
    val startMs: Long,
    val endMs: Long,
    /** 余弦相似度。只用于排序展示，绝不作绝对阈值（HANDOFF §7 约束3） */
    val score: Double,
)

/**
 * top-k 命中后合并相邻时间窗（HANDOFF §4）。
 * 仅按「同源 + 同模态 + 时间轴相邻(gap≤maxGap)」合并，分数取 max；结果按分数降序。
 */
object HitMerge {

    fun merge(hits: List<Hit>, maxGapMs: Long = 5_000): List<Hit> {
        if (hits.isEmpty()) return hits
        val ordered = hits.sortedWith(
            compareBy({ it.sourceId }, { it.modality }, { it.startMs })
        )
        val out = mutableListOf<Hit>()
        for (h in ordered) {
            val last = out.lastOrNull()
            val adjacent = last != null &&
                last.sourceId == h.sourceId &&
                last.modality == h.modality &&
                h.startMs - last.endMs <= maxGapMs // 重叠(负值)同样合并
            if (adjacent) {
                out[out.lastIndex] = last!!.copy(
                    startMs = minOf(last.startMs, h.startMs),
                    endMs = maxOf(last.endMs, h.endMs),
                    score = maxOf(last.score, h.score),
                )
            } else {
                out.add(h)
            }
        }
        return out.sortedByDescending { it.score }
    }
}
