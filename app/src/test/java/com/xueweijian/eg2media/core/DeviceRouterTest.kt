package com.xueweijian.eg2media.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HANDOFF §0 设备分级路由的行为契约（TDD：先锁行为）。
 * 依据 litert-community 实测文件清单（2026-10-08）：
 *  - EG2 740M NPU 版覆盖 SM8550/8650/8750/8850/QCS8275/MT6991/6993/Tensor G5/G6
 *  - E2B NPU 版只覆盖 SM8750/QCS8275/Tensor G5/G6（不对称！8 Gen 2/3 无 E2B NPU 版）
 *  - 未知 SoC / SM7475(marble 开发机) → 通用版 GPU/CPU
 *  - lowRam 或 memoryClass<128 → Tier3，E2B 门控关闭
 */
class DeviceRouterTest {

    @Test
    fun `旗舰 SM8550 路由到 EG2 NPU 版但 E2B 只有通用版`() {
        val r = DeviceRouter.route(socModel = "SM8550")
        assertEquals(DeviceTier.FLAGSHIP, r.tier)
        assertTrue(r.eg2Npu)
        assertEquals("embeddinggemma-2-740m_Qualcomm_SM8550.litertlm", r.eg2File)
        assertFalse(r.e2bNpu) // 不对称：E2B 无 SM8550 NPU 版
        assertTrue(r.allowE2B)
    }

    @Test
    fun `旗舰 SM8750 双 NPU`() {
        val r = DeviceRouter.route(socModel = "SM8750")
        assertTrue(r.eg2Npu)
        assertTrue(r.e2bNpu)
        assertEquals("gemma-4-E2B-it_qualcomm_sm8750.litertlm", r.e2bFile)
    }

    @Test
    fun `Tensor G5 双 NPU`() {
        val r = DeviceRouter.route(socModel = "Tensor G5")
        assertTrue(r.eg2Npu && r.e2bNpu)
        assertEquals("embeddinggemma-2-740m_Google_Tensor_G5.litertlm", r.eg2File)
    }

    @Test
    fun `联发科 MT6993 EG2 NPU 无 E2B NPU`() {
        val r = DeviceRouter.route(socModel = "MT6993")
        assertTrue(r.eg2Npu)
        assertEquals("embeddinggemma-2-740m_MediaTek_MT6993.litertlm", r.eg2File)
        assertFalse(r.e2bNpu)
    }

    @Test
    fun `中端 SM7475 marble 开发机走通用版`() {
        val r = DeviceRouter.route(socModel = "SM7475")
        assertEquals(DeviceTier.MAINSTREAM, r.tier)
        assertFalse(r.eg2Npu)
        assertEquals(DeviceRouter.EG2_GENERIC, r.eg2File)
        assertEquals(DeviceRouter.E2B_GENERIC, r.e2bFile)
        assertTrue(r.allowE2B)
    }

    @Test
    fun `未知 SoC 兜底通用版`() {
        val r = DeviceRouter.route(socModel = null)
        assertEquals(DeviceTier.MAINSTREAM, r.tier)
        assertFalse(r.eg2Npu)
        r.eg2File.let { assertTrue(it.endsWith(".litertlm")) }
    }

    @Test
    fun `大小写与空白归一`() {
        assertEquals(
            DeviceRouter.route("sm8550").eg2File,
            DeviceRouter.route("SM8550").eg2File,
        )
    }

    @Test
    fun `lowRam 设备 Tier3 且 E2B 门控关闭`() {
        val r = DeviceRouter.route(socModel = "SM8550", lowRam = true)
        assertEquals(DeviceTier.LOW_END, r.tier)
        assertFalse(r.allowE2B)
        assertFalse(r.eg2Npu) // 低配一律通用版，避免 NPU 驱动意外
    }

    @Test
    fun `memoryClass 低于 128 视为低配`() {
        val r = DeviceRouter.route(socModel = "SM7475", memoryClassMb = 96)
        assertEquals(DeviceTier.LOW_END, r.tier)
        assertFalse(r.allowE2B)
    }
}
