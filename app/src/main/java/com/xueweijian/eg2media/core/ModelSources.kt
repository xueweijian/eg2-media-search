package com.xueweijian.eg2media.core

/**
 * 模型分发源路由（HANDOFF §2.5）。
 * 下载器按序尝试：自托管(若配置) → hf-mirror（国内直连，HF 官方认可镜像）→ huggingface.co（海外）。
 * 文件名 → repo 的映射写死（litert-community 实测清单 2026-10）。
 */
object ModelSources {

    private const val MIRROR = "https://hf-mirror.com"
    private const val HF = "https://huggingface.co"

    /** 已知 repo：EG2 主档（含全部 NPU 变体）与 E2B 档 */
    private val repos = mapOf(
        "embeddinggemma-2-740m" to "litert-community/embeddinggemma-2-740m-litert-lm",
        "gemma-4-E2B-it" to "litert-community/gemma-4-E2B-it-litert-lm",
    )

    private val defaultSelfHosted = "" // 未配置时仅剩镜像+官方两源

    fun repoFor(fileName: String): String {
        val repo = repos.entries.firstOrNull { fileName.startsWith(it.key) }?.value
            ?: throw IllegalArgumentException("Unknown model file: $fileName")
        return repo
    }

    fun candidateUrls(fileName: String, selfHostedBase: String = defaultSelfHosted): List<String> {
        val repo = repoFor(fileName)
        val urls = buildList {
            if (selfHostedBase.isNotBlank()) {
                add(selfHostedBase.trimEnd('/') + "/" + fileName)
            }
            add("$MIRROR/$repo/resolve/main/$fileName")
            add("$HF/$repo/resolve/main/$fileName")
        }
        return urls
    }
}
