package com.example.armakeup.render

internal enum class MakeupRenderBackend {
    VULKAN,
    OPENGL,
}

internal data class RenderDeviceCapabilities(
    val sdkInt: Int,
    val processIs64Bit: Boolean,
    val hasVulkanHardwareLevel: Boolean,
    val vulkanApiVersion: Int,
)

/** Selects the requested production target; the temporary Filament bridge may still fall back. */
internal object RenderBackendPolicy {

    private const val MIN_VULKAN_SDK = 29
    private const val MAJOR_SHIFT = 22
    private const val MINOR_SHIFT = 12
    private const val MINOR_MASK = 0x3ff
    private const val PATCH_MASK = 0xfff

    const val VULKAN_1_1: Int = (1 shl MAJOR_SHIFT) or (1 shl MINOR_SHIFT)

    fun select(capabilities: RenderDeviceCapabilities): MakeupRenderBackend =
        if (
            capabilities.sdkInt >= MIN_VULKAN_SDK &&
            capabilities.processIs64Bit &&
            capabilities.hasVulkanHardwareLevel &&
            capabilities.vulkanApiVersion >= VULKAN_1_1
        ) {
            MakeupRenderBackend.VULKAN
        } else {
            MakeupRenderBackend.OPENGL
        }

    fun vulkanVersionName(version: Int): String {
        val major = version ushr MAJOR_SHIFT
        val minor = (version ushr MINOR_SHIFT) and MINOR_MASK
        val patch = version and PATCH_MASK
        return "$major.$minor.$patch"
    }
}
