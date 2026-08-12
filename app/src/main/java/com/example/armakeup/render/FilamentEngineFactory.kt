package com.example.armakeup.render

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.google.android.filament.Engine

/** Temporary V1 bridge. Native Vulkan ownership replaces this factory in migration stage V2. */
internal object FilamentEngineFactory {

    data class Selection(
        val engine: Engine,
        val materials: FilamentMaterialFactory.Materials,
        val requestedBackend: MakeupRenderBackend,
        val activeBackend: MakeupRenderBackend,
        val capabilities: RenderDeviceCapabilities,
        val fallbackReason: String?,
    ) {
        val diagnostic: String
            get() = buildString {
                append("requested=")
                append(requestedBackend)
                append(" active=")
                append(activeBackend)
                append(" sdk=")
                append(capabilities.sdkInt)
                append(" process64=")
                append(capabilities.processIs64Bit)
                append(" vulkan=")
                append(RenderBackendPolicy.vulkanVersionName(capabilities.vulkanApiVersion))
                fallbackReason?.let {
                    append(" fallback=")
                    append(it)
                }
            }
    }

    fun create(context: Context): Selection {
        val capabilities = readCapabilities(context.packageManager)
        val requested = RenderBackendPolicy.select(capabilities)
        return createSelection(
            requestedBackend = requested,
            activeBackend = MakeupRenderBackend.OPENGL,
            capabilities = capabilities,
            fallbackReason = if (requested == MakeupRenderBackend.VULKAN) {
                FILAMENT_VULKAN_CAMERA_UNAVAILABLE
            } else {
                null
            },
        )
    }

    private fun createSelection(
        requestedBackend: MakeupRenderBackend,
        activeBackend: MakeupRenderBackend,
        capabilities: RenderDeviceCapabilities,
        fallbackReason: String?,
    ): Selection {
        val engine = Engine.create(activeBackend.filamentBackend)
        return try {
            Selection(
                engine = engine,
                materials = FilamentMaterialFactory.build(engine, activeBackend),
                requestedBackend = requestedBackend,
                activeBackend = activeBackend,
                capabilities = capabilities,
                fallbackReason = fallbackReason,
            )
        } catch (error: RuntimeException) {
            engine.destroy()
            throw error
        }
    }

    private val MakeupRenderBackend.filamentBackend: Engine.Backend
        get() = when (this) {
            MakeupRenderBackend.VULKAN -> Engine.Backend.VULKAN
            MakeupRenderBackend.OPENGL -> Engine.Backend.OPENGL
        }

    private fun readCapabilities(packageManager: PackageManager): RenderDeviceCapabilities {
        val vulkanVersion = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.version
            ?: 0
        return RenderDeviceCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            processIs64Bit = Process.is64Bit(),
            hasVulkanHardwareLevel = packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
            ),
            vulkanApiVersion = vulkanVersion,
        )
    }

    private const val FILAMENT_VULKAN_CAMERA_UNAVAILABLE =
        "Filament Vulkan camera streams are unsupported; native renderer pending"
}
