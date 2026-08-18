package com.example.armakeup.render

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent native Vulkan runtime and V3 camera-buffer ownership bridge.
 *
 * A tiny ImageReader-backed Android Surface receives a real Vulkan swapchain. Native code owns the
 * instance, device, queue, command buffers and synchronization while Kotlin only drains and verifies
 * presented diagnostic frames. On supported devices V3 additionally creates the camera
 * AImageReader in native code, imports every latest-only AHardwareBuffer into the same VkDevice,
 * waits on the camera acquire sync-fd and exports a Vulkan release sync-fd before handing the
 * buffer to the temporary Filament/OpenGL compositor.
 */
internal class NativeVulkanDiagnosticRuntime private constructor(
    private val imageReader: ImageReader?,
    private val callbackThread: HandlerThread?,
    outputSurface: Surface,
    outputWidth: Int,
    outputHeight: Int,
) : Closeable {

    private val consumedFrameCount = AtomicLong(0L)
    private val cameraFrameCount = AtomicLong(0L)
    private val firstFrameReported = AtomicBoolean(false)
    private val cameraFrameReported = AtomicBoolean(false)
    private var nativeHandle = nativeCreate(
        outputSurface,
        outputWidth,
        outputHeight,
    )

    val isReady: Boolean
        get() = nativeHandle != 0L && nativeIsReady(nativeHandle)

    val isCameraBridgeReady: Boolean
        get() = nativeHandle != 0L && nativeIsCameraBridgeReady(nativeHandle)

    val diagnostic: String
        get() = if (nativeHandle == 0L) {
            "status=error reason=native_runtime_allocation_failed"
        } else {
            "${nativeDiagnostic(nativeHandle)} consumed=${consumedFrameCount.get()}"
        }

    init {
        if (imageReader != null && callbackThread != null) {
            imageReader.setOnImageAvailableListener(
                ::onImageAvailable,
                Handler(callbackThread.looper),
            )
        }
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

    fun configureCamera(width: Int, height: Int): Surface? {
        val handle = nativeHandle
        if (handle == 0L) return null
        return nativeConfigureCamera(handle, width, height)?.also {
            Log.i(LOG_TAG, "cameraConfigured=${width}x$height ${diagnostic}")
        }
    }

    fun acquireCameraFrame(
        transform: VulkanCameraTransform,
        landmarkSensorTimestampNs: Long?,
        trackingRoi: VulkanTemporalTrackingRoi,
    ): VulkanCameraFrameState? {
        val handle = nativeHandle
        if (handle == 0L) return null
        val metadata = LongArray(CAMERA_METADATA_COUNT)
        val temporalValues = FloatArray(VulkanTemporalTrackingResult.VALUE_COUNT)
        val hardwareBuffer = nativeAcquireCameraFrame(
            handle,
            metadata,
            transform.matrixCopy(),
            trackingRoi.toFloatArray(),
            temporalValues,
        ) ?: return null
        val temporalTracking = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = metadata[CAMERA_TEMPORAL_FROM_TIMESTAMP_INDEX],
            toSensorTimestampNs = metadata[CAMERA_TIMESTAMP_INDEX],
            values = temporalValues,
        )
        return VulkanCameraFrameState(
            hardwareBuffer = hardwareBuffer,
            nativeToken = metadata[CAMERA_TOKEN_INDEX],
            sensorTimestampNs = metadata[CAMERA_TIMESTAMP_INDEX],
            width = metadata[CAMERA_WIDTH_INDEX].toInt(),
            height = metadata[CAMERA_HEIGHT_INDEX].toInt(),
            hardwareBufferFormat = metadata[CAMERA_FORMAT_INDEX].toInt(),
            hardwareBufferUsage = metadata[CAMERA_USAGE_INDEX],
            transform = transform,
            landmarkSensorTimestampNs = landmarkSensorTimestampNs,
            acquireFenceImported = metadata[CAMERA_ACQUIRE_FENCE_INDEX] != 0L,
            releaseFenceExported = metadata[CAMERA_RELEASE_FENCE_INDEX] != 0L,
            temporalTrackingAttempted =
                metadata[CAMERA_TEMPORAL_FROM_TIMESTAMP_INDEX] > 0L,
            temporalTracking = temporalTracking,
        ).also { frame ->
            val count = cameraFrameCount.incrementAndGet()
            if (count % CAMERA_PROGRESS_INTERVAL == 0L) {
                val temporalRaw = temporalValues.joinToString(",", prefix = "[", postfix = "]") {
                    "%.4f".format(java.util.Locale.US, it)
                }
                Log.i(
                    LOG_TAG,
                    "cameraProgress delivered=$count timestampNs=${frame.sensorTimestampNs} " +
                        "landmarkAgeMs=${frame.landmarkAgeNs?.div(NANOS_PER_MILLISECOND)} " +
                        "temporalRaw=$temporalRaw " +
                        diagnostic,
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun releaseCameraFrame(frame: VulkanCameraFrameState) {
        val handle = nativeHandle
        if (handle != 0L) nativeReleaseCameraFrame(handle, frame.nativeToken)
        frame.hardwareBuffer.close()
    }

    fun closeCamera() {
        val handle = nativeHandle
        if (handle != 0L) nativeCloseCamera(handle)
    }

    fun updateTrackingTestLip(vertices: FloatArray, indices: ShortArray, visible: Boolean): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeUpdateTrackingTestLip(handle, vertices, indices, visible)
    }

    fun latestPresentationSample(): NativeVulkanPresentationSample? {
        val handle = nativeHandle
        if (handle == 0L) return null
        val values = LongArray(PRESENTATION_METADATA_COUNT)
        if (!nativeReadLatestPresentationTiming(handle, values)) return null
        val monotonicToElapsedOffsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        return NativeVulkanPresentationSample(
            presentationId = values[PRESENTATION_ID_INDEX],
            cameraSensorTimestampNs = values[PRESENTATION_CAMERA_TIMESTAMP_INDEX],
            actualPresentationTimestampNs =
                values[PRESENTATION_ACTUAL_TIMESTAMP_INDEX] + monotonicToElapsedOffsetNs,
            desiredPresentationTimestampNs =
                values[PRESENTATION_DESIRED_TIMESTAMP_INDEX] + monotonicToElapsedOffsetNs,
            earliestPresentationTimestampNs =
                values[PRESENTATION_EARLIEST_TIMESTAMP_INDEX] + monotonicToElapsedOffsetNs,
            presentMarginNs = values[PRESENTATION_MARGIN_INDEX],
            refreshDurationNs = values[PRESENTATION_REFRESH_DURATION_INDEX],
        )
    }

    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            nativeStop(handle)
            nativeCloseCamera(handle)
            nativeDestroy(handle)
        }
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        callbackThread?.quitSafely()
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
            val sample = sampleCenterPixel(image)
            if (firstFrameReported.compareAndSet(false, true)) {
                Log.i(
                    LOG_TAG,
                    "firstFrame consumed=$consumed sample=$sample",
                )
            }
            if (
                sample.cameraColorVisible &&
                cameraFrameReported.compareAndSet(false, true)
            ) {
                Log.i(LOG_TAG, "cameraFrameVisible consumed=$consumed sample=${sample.description}")
            }
        }
    }

    private fun sampleCenterPixel(image: Image): PixelSample {
        val plane = image.planes.firstOrNull()
            ?: return PixelSample("unavailable:no_plane", cameraColorVisible = false)
        if (plane.pixelStride < RGBA_CHANNEL_COUNT) {
            return PixelSample(
                "unavailable:pixel_stride_${plane.pixelStride}",
                cameraColorVisible = false,
            )
        }
        val x = image.width / 2
        val y = image.height / 2
        val offset = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer
        if (offset < 0 || offset + RGBA_CHANNEL_COUNT > buffer.limit()) {
            return PixelSample("unavailable:buffer_bounds", cameraColorVisible = false)
        }

        val red = buffer.get(offset).toInt() and BYTE_MASK
        val green = buffer.get(offset + 1).toInt() and BYTE_MASK
        val blue = buffer.get(offset + 2).toInt() and BYTE_MASK
        val alpha = buffer.get(offset + 3).toInt() and BYTE_MASK
        val clearVisible = alpha >= MIN_CLEAR_ALPHA && maxOf(red, green, blue) >= MIN_CLEAR_COLOR
        val differsFromClear =
            kotlin.math.abs(red - DIAGNOSTIC_CLEAR_RED) > CLEAR_COLOR_TOLERANCE ||
                kotlin.math.abs(green - DIAGNOSTIC_CLEAR_GREEN) > CLEAR_COLOR_TOLERANCE ||
                kotlin.math.abs(blue - DIAGNOSTIC_CLEAR_BLUE) > CLEAR_COLOR_TOLERANCE
        val cameraColorVisible = alpha >= MIN_CLEAR_ALPHA && differsFromClear &&
            maxOf(red, green, blue) >= MIN_CAMERA_COLOR
        return PixelSample(
            description = "rgba=$red,$green,$blue,$alpha clearVisible=$clearVisible " +
                "cameraColorVisible=$cameraColorVisible",
            cameraColorVisible = cameraColorVisible,
        )
    }

    private data class PixelSample(
        val description: String,
        val cameraColorVisible: Boolean,
    ) {
        override fun toString(): String = description
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
    private external fun nativeConfigureCamera(handle: Long, width: Int, height: Int): Surface?
    private external fun nativeIsCameraBridgeReady(handle: Long): Boolean
    private external fun nativeAcquireCameraFrame(
        handle: Long,
        metadata: LongArray,
        uvTransform: FloatArray,
        trackingRoi: FloatArray,
        temporalValues: FloatArray,
    ): HardwareBuffer?
    private external fun nativeReleaseCameraFrame(handle: Long, token: Long)
    private external fun nativeCloseCamera(handle: Long)
    private external fun nativeUpdateTrackingTestLip(
        handle: Long,
        vertices: FloatArray,
        indices: ShortArray,
        visible: Boolean,
    ): Boolean
    private external fun nativeReadLatestPresentationTiming(
        handle: Long,
        values: LongArray,
    ): Boolean

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
        private const val MIN_CAMERA_COLOR = 8
        private const val DIAGNOSTIC_CLEAR_RED = 209
        private const val DIAGNOSTIC_CLEAR_GREEN = 31
        private const val DIAGNOSTIC_CLEAR_BLUE = 87
        private const val CLEAR_COLOR_TOLERANCE = 12
        private const val CAMERA_METADATA_COUNT = 9
        private const val CAMERA_TOKEN_INDEX = 0
        private const val CAMERA_TIMESTAMP_INDEX = 1
        private const val CAMERA_WIDTH_INDEX = 2
        private const val CAMERA_HEIGHT_INDEX = 3
        private const val CAMERA_FORMAT_INDEX = 4
        private const val CAMERA_USAGE_INDEX = 5
        private const val CAMERA_ACQUIRE_FENCE_INDEX = 6
        private const val CAMERA_RELEASE_FENCE_INDEX = 7
        private const val CAMERA_TEMPORAL_FROM_TIMESTAMP_INDEX = 8
        private const val CAMERA_PROGRESS_INTERVAL = 60L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val PRESENTATION_METADATA_COUNT = 7
        private const val PRESENTATION_ID_INDEX = 0
        private const val PRESENTATION_CAMERA_TIMESTAMP_INDEX = 1
        private const val PRESENTATION_ACTUAL_TIMESTAMP_INDEX = 2
        private const val PRESENTATION_DESIRED_TIMESTAMP_INDEX = 3
        private const val PRESENTATION_EARLIEST_TIMESTAMP_INDEX = 4
        private const val PRESENTATION_MARGIN_INDEX = 5
        private const val PRESENTATION_REFRESH_DURATION_INDEX = 6

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
                NativeVulkanDiagnosticRuntime(
                    imageReader = imageReader,
                    callbackThread = callbackThread,
                    outputSurface = imageReader.surface,
                    outputWidth = DIAGNOSTIC_WIDTH,
                    outputHeight = DIAGNOSTIC_HEIGHT,
                ).also { runtime ->
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

        fun createVisibleOrNull(
            probe: NativeVulkanBootstrap.Probe,
            surface: Surface,
            width: Int,
            height: Int,
        ): NativeVulkanDiagnosticRuntime? {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                !probe.vulkanAvailable ||
                !surface.isValid ||
                width <= 0 ||
                height <= 0
            ) {
                return null
            }
            return runCatching {
                NativeVulkanDiagnosticRuntime(
                    imageReader = null,
                    callbackThread = null,
                    outputSurface = surface,
                    outputWidth = width,
                    outputHeight = height,
                ).also { runtime ->
                    if (!runtime.isReady) {
                        val diagnostic = runtime.diagnostic
                        runtime.close()
                        error("Native Vulkan visible runtime unavailable: $diagnostic")
                    }
                    Log.i(LOG_TAG, "visibleCreated ${runtime.diagnostic}")
                }
            }.onFailure { error ->
                Log.e(LOG_TAG, "visible creation failed", error)
            }.getOrNull()
        }
    }
}

internal data class NativeVulkanPresentationSample(
    val presentationId: Long,
    val cameraSensorTimestampNs: Long,
    val actualPresentationTimestampNs: Long,
    val desiredPresentationTimestampNs: Long,
    val earliestPresentationTimestampNs: Long,
    val presentMarginNs: Long,
    val refreshDurationNs: Long,
) {
    val sensorToActualMs: Float
        get() = if (cameraSensorTimestampNs > 0L && actualPresentationTimestampNs > 0L) {
            (actualPresentationTimestampNs - cameraSensorTimestampNs) / 1_000_000f
        } else {
            Float.NaN
        }
}
