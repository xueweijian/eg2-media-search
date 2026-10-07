package com.xueweijian.eg2media.core

/** 索引记录的模态。VIDEO_AUDIO = 视频拆出的音轨（与纯录音区分） */
enum class Modality(val code: String) {
    IMAGE("img"),
    VIDEO_FRAME("vf"),
    VIDEO_AUDIO("va"),
    AUDIO("aud"),
    DOC_CHUNK("doc");

    companion object {
        fun fromCode(code: String): Modality =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown modality code: $code")
    }
}
