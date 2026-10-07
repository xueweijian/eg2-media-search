package com.xueweijian.eg2media.core

/**
 * 文档分块（HANDOFF §4）：中文友好的句子边界 + 重叠滑窗。
 * - 边界字符：。！？!?…\n（句子完整性优先于长度精确）
 * - 单句超过 target*1.5 才硬切（OCR 长段无标点场景）
 * - 相邻块重叠 overlapChars，保证跨块语义不丢
 */
object TextChunker {

    private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '…', '\n')

    data class Chunk(val index: Int, val text: String)

    fun chunk(text: String, targetChars: Int = 700, overlapChars: Int = 100): List<Chunk> {
        require(targetChars > overlapChars) { "target must > overlap" }
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        val sentences = splitSentences(clean, targetChars + targetChars / 2)

        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length > targetChars) {
                out.add(cur.toString())
                // 重叠：保留上一块尾部；超长句独立成块不加重叠（保证长度上界）
                val tail = cur.toString().takeLast(overlapChars)
                cur.setLength(0)
                if (tail.isNotEmpty() && s.length <= targetChars) cur.append(tail)
            }
            cur.append(s)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())

        return out.mapIndexed { i, t -> Chunk(i, t) }
    }

    private fun splitSentences(text: String, hardLimit: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            sb.append(c)
            if (c in SENTENCE_END) {
                out.add(sb.toString())
                sb.clear()
            } else if (sb.length >= hardLimit) {
                // 无边界长段硬切
                out.add(sb.toString())
                sb.clear()
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
