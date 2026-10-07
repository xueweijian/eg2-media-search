package com.xueweijian.eg2media.core

enum class DeviceTier { FLAGSHIP, MAINSTREAM, LOW_END }

data class ModelRoute(
    val tier: DeviceTier,
    /** litert-community 上的 EG2 文件名 */
    val eg2File: String,
    val eg2Npu: Boolean,
    /** litert-community 上的 Gemma4 E2B 文件名 */
    val e2bFile: String,
    val e2bNpu: Boolean,
    /** 低配门控：false 时 UI 隐藏 E2B 入口（HANDOFF §0 Tier3） */
    val allowE2B: Boolean,
)

/**
 * 设备分级路由（HANDOFF §0）。
 * 数据源：litert-community 实测文件清单（2026-10-08）。
 * 关键不对称：EG2 NPU 版覆盖 SM8550~8850 全系；E2B NPU 版只有 SM8750/QCS8275/Tensor G5/G6。
 */
object DeviceRouter {
    const val EG2_GENERIC = "embeddinggemma-2-740m.litertlm"
    const val E2B_GENERIC = "gemma-4-E2B-it.litertlm"

    private val eg2NpuSoCs: Map<String, String> = mapOf(
        "SM8550" to "Qualcomm_SM8550",
        "SM8650" to "Qualcomm_SM8650",
        "SM8750" to "Qualcomm_SM8750",
        "SM8850" to "Qualcomm_SM8850",
        "QCS8275" to "Qualcomm_QCS8275",
        "MT6991" to "MediaTek_MT6991",
        "MT6993" to "MediaTek_MT6993",
        "TENSOR G5" to "Google_Tensor_G5",
        "TENSOR G6" to "Google_Tensor_G6",
    )

    private val e2bNpuSoCs: Map<String, String> = mapOf(
        "SM8750" to "qualcomm_sm8750",
        "QCS8275" to "qualcomm_qcs8275",
        "TENSOR G5" to "Google_Tensor_G5",
        "TENSOR G6" to "Google_Tensor_G6",
    )

    fun route(
        socModel: String?,
        lowRam: Boolean = false,
        memoryClassMb: Int = 256,
    ): ModelRoute {
        val soc = socModel?.trim()?.uppercase()
        if (lowRam || memoryClassMb < 128) {
            // 低配：一律通用版 + E2B 门控关闭（NPU 驱动与内存都不可信）
            return ModelRoute(DeviceTier.LOW_END, EG2_GENERIC, false, E2B_GENERIC, false, allowE2B = false)
        }
        val eg2Suffix = soc?.let { eg2NpuSoCs[it] }
        val e2bSuffix = soc?.let { e2bNpuSoCs[it] }
        return if (eg2Suffix != null) {
            ModelRoute(
                tier = DeviceTier.FLAGSHIP,
                eg2File = "embeddinggemma-2-740m_${eg2Suffix}.litertlm",
                eg2Npu = true,
                e2bFile = e2bSuffix?.let { "gemma-4-E2B-it_${it}.litertlm" } ?: E2B_GENERIC,
                e2bNpu = e2bSuffix != null,
                allowE2B = true,
            )
        } else {
            ModelRoute(DeviceTier.MAINSTREAM, EG2_GENERIC, false, E2B_GENERIC, false, allowE2B = true)
        }
    }
}
