package com.xueweijian.eg2media.core

data class RecordRef(
    val sourceId: String,
    val modality: Modality,
    /** 区间 [startMs, endMs)。整档记录（图/文档块）用 0-0 */
    val startMs: Long,
    val endMs: Long,
)

/**
 * 时间戳自管方案（HANDOFF §4）：SemanticRetriever 的 insertImage/insertAudio 无时间戳字段，
 * 把区间编码进记录 ID："sourceId|modalityCode|start-end"。
 * sourceId 用内部 UUID/自增 ID，禁止包含 '|'。
 */
object RecordIds {

    fun encode(ref: RecordRef): String {
        require(ref.sourceId.none { it == '|' }) { "sourceId 不允许竖线字符" }
        return "${ref.sourceId}|${ref.modality.code}|${ref.startMs}-${ref.endMs}"
    }

    fun decode(id: String): RecordRef {
        val parts = id.split('|')
        require(parts.size == 3) { "Illegal record id: $id" }
        val range = parts[2].split('-')
        require(range.size == 2) { "Illegal time range in: $id" }
        return RecordRef(
            sourceId = parts[0],
            modality = Modality.fromCode(parts[1]),
            startMs = range[0].toLong(),
            endMs = range[1].toLong(),
        )
    }
}
