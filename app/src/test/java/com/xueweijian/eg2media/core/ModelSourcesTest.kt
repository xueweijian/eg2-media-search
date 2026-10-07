package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HANDOFF §2.5：国内 HF 直连不通，下载源按序回退 hf-mirror → 自托管 → 官方 */
class ModelSourcesTest {

    @Test
    fun `默认双源 mirror 优先`() {
        val urls = ModelSources.candidateUrls("embeddinggemma-2-740m.litertlm")
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://hf-mirror.com/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/"))
        assertTrue(urls[1].startsWith("https://huggingface.co/litert-community/"))
    }

    @Test
    fun `E2B 文件路由到对应 repo`() {
        val urls = ModelSources.candidateUrls("gemma-4-E2B-it.litertlm")
        assertTrue(urls[0].contains("/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun `NPU 变体文件名保持原样`() {
        val urls = ModelSources.candidateUrls("embeddinggemma-2-740m_Qualcomm_SM8550.litertlm")
        assertTrue(urls[0].endsWith("/embeddinggemma-2-740m_Qualcomm_SM8550.litertlm"))
    }

    @Test
    fun `repo 名由文件名前缀推断`() {
        assertEquals("embeddinggemma-2-740m-litert-lm", ModelSources.repoFor("embeddinggemma-2-740m.litertlm"))
        assertEquals("gemma-4-E2B-it-litert-lm", ModelSources.repoFor("gemma-4-E2B-it.litertlm"))
        assertEquals("gemma-4-E2B-it-litert-lm", ModelSources.repoFor("gemma-4-E2B-it_qualcomm_sm8750.litertlm"))
    }

    @Test
    fun `未知文件名抛异常`() {
        try {
            ModelSources.candidateUrls("random-model.bin")
            throw AssertionError("should reject unknown file")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `自托管源可覆盖且排第一`() {
        val urls = ModelSources.candidateUrls(
            "embeddinggemma-2-740m.litertlm",
            selfHostedBase = "https://cdn.example.com/models",
        )
        assertEquals("https://cdn.example.com/models/embeddinggemma-2-740m.litertlm", urls.first())
        assertEquals(3, urls.size)
    }
}
