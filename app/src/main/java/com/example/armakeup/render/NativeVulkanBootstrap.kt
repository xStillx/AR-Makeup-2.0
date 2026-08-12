package com.example.armakeup.render

/** Verifies the native Vulkan loader independently from Filament before frame-graph ownership moves. */
internal object NativeVulkanBootstrap {

    data class Probe(
        val libraryLoaded: Boolean,
        val vulkanAvailable: Boolean,
        val diagnostic: String,
    )

    private val libraryLoadFailure: Throwable? by lazy {
        runCatching { System.loadLibrary(NATIVE_LIBRARY) }.exceptionOrNull()
    }

    fun probe(): Probe {
        libraryLoadFailure?.let { failure ->
            return Probe(
                libraryLoaded = false,
                vulkanAvailable = false,
                diagnostic = "status=unavailable reason=native_library_load_failed " +
                    "detail=${failure.javaClass.simpleName}",
            )
        }

        return runCatching { nativeProbe() }
            .fold(
                onSuccess = { diagnostic ->
                    Probe(
                        libraryLoaded = true,
                        vulkanAvailable = diagnostic.startsWith("status=ok"),
                        diagnostic = diagnostic,
                    )
                },
                onFailure = { failure ->
                    Probe(
                        libraryLoaded = true,
                        vulkanAvailable = false,
                        diagnostic = "status=error reason=native_probe_failed " +
                            "detail=${failure.javaClass.simpleName}",
                    )
                },
            )
    }

    private external fun nativeProbe(): String

    private const val NATIVE_LIBRARY = "armakeup_vulkan"
}
