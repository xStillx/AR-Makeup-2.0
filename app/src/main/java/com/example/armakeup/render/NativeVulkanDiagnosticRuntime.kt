package com.example.armakeup.render

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V2.1 offscreen proof for persistent native Vulkan ownership.
 *
 * A tiny ImageReader-backed Android Surface receives a real Vulkan swapchain. Native code owns the
 * instance, device, queue, command buffers and synchronization while Kotlin only drains and verifies
 * presented diagnostic frames. The production camera surface remains owned by the Filament/OpenGL
 * bridge until V3 implements direct AHardwareBuffer import and fences.
 */
internal class NativeVulkanDiagnosticRuntime private constructor(
    private val imageReader: ImageReader,
    private val callbackThread: HandlerThread,
) : Closeable {

    private val consumedFrameCount = AtomicLong(0L)
    private val firstFrameReported = AtomicBoolean(false)
    private var nativeHandle = nativeCreate(
        imageReader.surface,
        DIAGNOSTIC_WIDTH,
        DIAGNOSTIC_HEIGHT,
    )

    val isReady: Boolean
        get() = nativeHandle != 0L && nativeIsReady(nativeHandle)

    val diagnostic: String
        get() = if (nativeHandle == 0L) {
            "status=error reason=native_runtime_allocation_failed"
        } else {
            "${nativeDiagnostic(nativeHandle)} consumed=${consumedFrameCount.get()}"
        }

    init {
        imageReader.setOnImageAvailableListener(::onImageAvailable, Handler(callbackThread.looper))
    }

    fun start() {
        val handle = nativeHandle
        if (handle == 0L) return
        val started = nativeStart(handle)
        Log.i(LOG_TAG, "start=$started ${diagnostic}")
    }

    fun stop() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeStop(handle)
        Log.i(LOG_TAG, "stopped ${diagnostic}")
    }

    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            nativeStop(handle)
            nativeDestroy(handle)
        }
        imageReader.setOnImageAvailableListener(null, null)
        imageReader.close()
        callbackThread.quitSafely()
        Log.i(LOG_TAG, "destroyed consumed=${consumedFrameCount.get()}")
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        image.use {
            val consumed = consumedFrameCount.incrementAndGet()
            if (firstFrameReported.compareAndSet(false, true)) {
                val sample = sampleCenterPixel(image)
                Log.i(
                    LOG_TAG,
                    "firstFrame consumed=$consumed sample=$sample",
                )
            }
        }
    }

    private fun sampleCenterPixel(image: Image): String {
        val plane = image.planes.firstOrNull() ?: return "unavailable:no_plane"
        if (plane.pixelStride < RGBA_CHANNEL_COUNT) {
            return "unavailable:pixel_stride_${plane.pixelStride}"
        }
        val x = image.width / 2
        val y = image.height / 2
        val offset = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer
        if (offset < 0 || offset + RGBA_CHANNEL_COUNT > buffer.limit()) {
            return "unavailable:buffer_bounds"
        }

        val red = buffer.get(offset).toInt() and BYTE_MASK
        val green = buffer.get(offset + 1).toInt() and BYTE_MASK
        val blue = buffer.get(offset + 2).toInt() and BYTE_MASK
        val alpha = buffer.get(offset + 3).toInt() and BYTE_MASK
        val clearVisible = alpha >= MIN_CLEAR_ALPHA && maxOf(red, green, blue) >= MIN_CLEAR_COLOR
        return "rgba=$red,$green,$blue,$alpha clearVisible=$clearVisible"
    }

    private external fun nativeCreate(
        surface: android.view.Surface,
        width: Int,
        height: Int,
    ): Long

    private external fun nativeIsReady(handle: Long): Boolean
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeDiagnostic(handle: Long): String

    companion object {
        private const val LOG_TAG = "ARMakeupVulkanRuntime"
        private const val NATIVE_LIBRARY = "armakeup_vulkan"
        private const val DIAGNOSTIC_WIDTH = 64
        private const val DIAGNOSTIC_HEIGHT = 64
        private const val DIAGNOSTIC_MAX_IMAGES = 3
        private const val RGBA_CHANNEL_COUNT = 4
        private const val BYTE_MASK = 0xff
        private const val MIN_CLEAR_ALPHA = 200
        private const val MIN_CLEAR_COLOR = 96

        init {
            System.loadLibrary(NATIVE_LIBRARY)
        }

        fun createOrNull(probe: NativeVulkanBootstrap.Probe): NativeVulkanDiagnosticRuntime? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !probe.vulkanAvailable) return null
            return createApi29()
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun createApi29(): NativeVulkanDiagnosticRuntime? {
            var pendingThread: HandlerThread? = null
            var pendingReader: ImageReader? = null
            return runCatching {
                val callbackThread = HandlerThread("ar-makeup-vulkan-diagnostic").apply { start() }
                pendingThread = callbackThread
                val imageReader = try {
                    ImageReader.newInstance(
                        DIAGNOSTIC_WIDTH,
                        DIAGNOSTIC_HEIGHT,
                        PixelFormat.RGBA_8888,
                        DIAGNOSTIC_MAX_IMAGES,
                        HardwareBuffer.USAGE_CPU_READ_RARELY,
                    )
                } catch (error: Throwable) {
                    callbackThread.quitSafely()
                    pendingThread = null
                    throw error
                }
                pendingReader = imageReader
                NativeVulkanDiagnosticRuntime(imageReader, callbackThread).also { runtime ->
                    pendingReader = null
                    pendingThread = null
                    if (!runtime.isReady) {
                        val diagnostic = runtime.diagnostic
                        runtime.close()
                        error("Native Vulkan diagnostic runtime unavailable: $diagnostic")
                    }
                    Log.i(LOG_TAG, "created ${runtime.diagnostic}")
                }
            }.onFailure { error ->
                pendingReader?.close()
                pendingThread?.quitSafely()
                Log.e(LOG_TAG, "creation failed", error)
            }.getOrNull()
        }
    }
}
