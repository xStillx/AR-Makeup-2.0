package com.example.armakeup.render

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderBackendPolicyTest {

    @Test
    fun selectsVulkanForApi29Plus64BitVulkan11Device() {
        val selected = RenderBackendPolicy.select(
            capabilities(
                sdkInt = 29,
                vulkanApiVersion = RenderBackendPolicy.VULKAN_1_1,
            ),
        )

        assertEquals(MakeupRenderBackend.VULKAN, selected)
    }

    @Test
    fun keepsOpenGlFallbackBelowApi29() {
        val selected = RenderBackendPolicy.select(
            capabilities(
                sdkInt = 28,
                vulkanApiVersion = RenderBackendPolicy.VULKAN_1_1,
            ),
        )

        assertEquals(MakeupRenderBackend.OPENGL, selected)
    }

    @Test
    fun keepsOpenGlFallbackFor32BitProcess() {
        val selected = RenderBackendPolicy.select(
            capabilities(processIs64Bit = false),
        )

        assertEquals(MakeupRenderBackend.OPENGL, selected)
    }

    @Test
    fun keepsOpenGlFallbackWithoutVulkan11() {
        val selected = RenderBackendPolicy.select(
            capabilities(vulkanApiVersion = makeVulkanVersion(1, 0, 198)),
        )

        assertEquals(MakeupRenderBackend.OPENGL, selected)
    }

    @Test
    fun keepsOpenGlFallbackWithoutVulkanHardwareLevel() {
        val selected = RenderBackendPolicy.select(
            capabilities(hasVulkanHardwareLevel = false),
        )

        assertEquals(MakeupRenderBackend.OPENGL, selected)
    }

    @Test
    fun formatsAdvertisedVulkanVersion() {
        assertEquals(
            "1.3.275",
            RenderBackendPolicy.vulkanVersionName(makeVulkanVersion(1, 3, 275)),
        )
    }

    private fun capabilities(
        sdkInt: Int = 36,
        processIs64Bit: Boolean = true,
        hasVulkanHardwareLevel: Boolean = true,
        vulkanApiVersion: Int = makeVulkanVersion(1, 3, 275),
    ) = RenderDeviceCapabilities(
        sdkInt = sdkInt,
        processIs64Bit = processIs64Bit,
        hasVulkanHardwareLevel = hasVulkanHardwareLevel,
        vulkanApiVersion = vulkanApiVersion,
    )

    private fun makeVulkanVersion(major: Int, minor: Int, patch: Int): Int =
        (major shl 22) or (minor shl 12) or patch
}
