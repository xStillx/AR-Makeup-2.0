package com.example.armakeup.render

import android.content.Context
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.camera.core.SurfaceRequest
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.armakeup.R
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import com.example.armakeup.tracking.AndroidGyroscopeSource
import com.example.armakeup.tracking.CameraProjectionCalibration
import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.GyroscopeLipCompensator
import com.example.armakeup.tracking.GyroscopeRotationHistory
import com.example.armakeup.tracking.LandmarkRenderFrame
import com.example.armakeup.tracking.NormalizedImageTransform
import com.example.armakeup.tracking.TrackingTelemetrySink

/** Debug-only visible swapchain proof. Filament is not constructed while this renderer is active. */
internal class NativeVulkanVisibleRenderer(
    context: Context,
    private val surfaceView: SurfaceView,
    private val onError: (String) -> Unit,
) : MakeupRendererController, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val choreographer = Choreographer.getInstance()
    private val nativeProbe = NativeVulkanBootstrap.probe()
    private val lipTessellator = LipMeshTessellator()
    private val outerPoints = FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val innerPoints = FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)
    private val nativeLipVertices = FloatArray(lipTessellator.vertexCount * NATIVE_VERTEX_COMPONENTS)
    private val gyroscopeHistory = GyroscopeRotationHistory()
    private val gyroscopeCompensator = GyroscopeLipCompensator(gyroscopeHistory)
    private val gyroscopeSource = AndroidGyroscopeSource(
        context = context.applicationContext,
        history = gyroscopeHistory,
        handler = mainHandler,
    )

    private var runtime: NativeVulkanDiagnosticRuntime? = null
    private var activeSurfaceRequest: SurfaceRequest? = null
    private var latestCameraTransform: VulkanCameraTransform? = null
    private var latestLandmarks: LandmarkState? = null
    private var cameraCalibration: CameraProjectionCalibration? = null
    private var latestCameraSensorTimestampNs = NO_TIMESTAMP
    private var trackingTelemetrySink: TrackingTelemetrySink? = null
    private var gyroscopeCorrectionEnabled = true
    private var resumed = false
    private var frameCallbackPosted = false
    private var destroyRequested = false
    private var destroyed = false
    private var fatalErrorDelivered = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lastPresentationId = 0L
    private var lastActualPresentationTimestampNs = 0L
    private var lastPresentedCameraTimestampNs = NO_TIMESTAMP
    private var presentationSampleCount = 0L
    private var reusedCameraPresentationCount = 0L
    private val sensorToActualSamplesMs = ArrayList<Float>(MAX_TIMING_SAMPLES)
    private val actualIntervalSamplesMs = ArrayList<Float>(MAX_TIMING_SAMPLES)

    override val renderBackendLabelRes: Int
        get() = R.string.render_backend_native_vulkan_visible

    init {
        check(nativeProbe.vulkanAvailable) {
            "Native Vulkan visible proof unavailable: ${nativeProbe.diagnostic}"
        }
        surfaceView.holder.addCallback(this)
        Log.i(LOG_TAG, "created ${nativeProbe.diagnostic}")
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ensureMainThread()
        if (destroyRequested || destroyed || runtime != null) return
        viewportWidth = width
        viewportHeight = height
        val created = NativeVulkanDiagnosticRuntime.createVisibleOrNull(
            probe = nativeProbe,
            surface = holder.surface,
            width = width,
            height = height,
        )
        if (created == null) {
            reportFatalError("Failed to create native Vulkan visible swapchain")
            return
        }
        runtime = created
        providePendingCameraSurface()
        postFrameCallback()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ensureMainThread()
        removeFrameCallback()
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        ensureMainThread()
        if (destroyRequested || destroyed) {
            request.willNotProvideSurface()
            return
        }
        if (activeSurfaceRequest != null) {
            request.willNotProvideSurface()
            reportFatalError("CameraX requested overlapping native Vulkan camera surfaces")
            return
        }
        activeSurfaceRequest = request
        providePendingCameraSurface()
    }

    private fun providePendingCameraSurface() {
        val request = activeSurfaceRequest ?: return
        val activeRuntime = runtime ?: return
        val cameraSurface = activeRuntime.configureCamera(
            request.resolution.width,
            request.resolution.height,
        ) ?: run {
            activeSurfaceRequest = null
            request.willNotProvideSurface()
            reportFatalError("Native Vulkan camera surface creation failed")
            return
        }
        request.setTransformationInfoListener(mainExecutor) { info ->
            if (activeSurfaceRequest === request) applyCameraTransform(request, info)
        }
        request.provideSurface(cameraSurface, mainExecutor) {
            cameraSurface.release()
            if (activeSurfaceRequest === request) activeSurfaceRequest = null
            if (destroyRequested) finishDestroy()
        }
    }

    private fun applyCameraTransform(
        request: SurfaceRequest,
        info: SurfaceRequest.TransformationInfo,
    ) {
        val crop = info.cropRect
        val matrix = CameraTextureTransform.matrix(
            bufferWidth = request.resolution.width,
            bufferHeight = request.resolution.height,
            crop = CameraTextureTransform.CropRegion(
                crop.left,
                crop.top,
                crop.right,
                crop.bottom,
            ),
            rotationDegrees = info.rotationDegrees,
            mirrorHorizontal = info.isMirroring,
            invertDisplayHorizontally = true,
            invertDisplayVertically = true,
        )
        latestCameraTransform = VulkanCameraTransform(
            cropLeft = crop.left,
            cropTop = crop.top,
            cropRight = crop.right,
            cropBottom = crop.bottom,
            rotationDegrees = normalizeRotation(info.rotationDegrees),
            mirrorHorizontal = info.isMirroring,
            matrix = matrix,
        )
        Log.i(
            LOG_TAG,
            "cameraTransform rotation=${info.rotationDegrees} mirror=${info.isMirroring} " +
                "crop=${crop.left},${crop.top},${crop.right},${crop.bottom}",
        )
    }

    override fun setTrackingTelemetrySink(sink: TrackingTelemetrySink?) {
        ensureMainThread()
        trackingTelemetrySink = sink
    }

    override fun setCameraProjectionCalibration(calibration: CameraProjectionCalibration?) {
        ensureMainThread()
        cameraCalibration = calibration
    }

    override fun setGyroscopeCorrectionEnabled(enabled: Boolean) {
        ensureMainThread()
        if (gyroscopeCorrectionEnabled == enabled) return
        gyroscopeCorrectionEnabled = enabled
        gyroscopeHistory.clear()
        if (resumed) {
            if (enabled) gyroscopeSource.start() else gyroscopeSource.stop()
        }
    }

    override fun setResult(
        landmarks: LandmarkRenderFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        sensorTimestampNs: Long,
    ) {
        ensureMainThread()
        latestLandmarks = LandmarkState(
            landmarks = landmarks,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            mirrorHorizontal = mirrorHorizontal,
            sensorTimestampNs = sensorTimestampNs,
        )
    }

    override fun clear() {
        ensureMainThread()
        latestLandmarks = null
        gyroscopeHistory.clear()
        runtime?.updateTrackingTestLip(EMPTY_FLOATS, EMPTY_SHORTS, visible = false)
    }

    override fun setLipstickFinish(finish: LipstickFinish) {
        if (finish != LipstickFinish.TRACKING_TEST) {
            Log.i(LOG_TAG, "finish=${finish.name} ignored; visible proof uses TRACKING_TEST")
        }
    }

    override fun resume() {
        ensureMainThread()
        if (destroyRequested || destroyed || resumed) return
        resumed = true
        if (gyroscopeCorrectionEnabled) gyroscopeSource.start()
        postFrameCallback()
    }

    override fun pause() {
        ensureMainThread()
        if (!resumed) return
        resumed = false
        removeFrameCallback()
        gyroscopeSource.stop()
    }

    override fun destroy() {
        ensureMainThread()
        if (destroyRequested || destroyed) return
        destroyRequested = true
        pause()
        surfaceView.holder.removeCallback(this)
        val request = activeSurfaceRequest
        if (request == null) {
            finishDestroy()
        } else {
            request.invalidate()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun doFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        if (!resumed || destroyRequested || destroyed) return
        val activeRuntime = runtime
        val transform = latestCameraTransform
        try {
            if (activeRuntime != null && transform != null) {
                val retainedCameraTimestampNs = activeRuntime.updateVisibleCamera(transform)
                if (retainedCameraTimestampNs > 0L) {
                    latestCameraSensorTimestampNs = retainedCameraTimestampNs
                    updateTrackingTestLip(
                        activeRuntime,
                        frameTimeNanos / NANOS_PER_MILLISECOND,
                    )
                    check(activeRuntime.presentVisibleFrame()) {
                        "Native Vulkan retained-camera present failed"
                    }
                }
                reportPresentationTiming(activeRuntime)
            }
        } catch (error: RuntimeException) {
            reportFatalError(error.message ?: "Native Vulkan visible render failure")
            return
        }
        postFrameCallback()
    }

    private fun updateTrackingTestLip(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        renderTimestampMs: Long,
    ) {
        val state = latestLandmarks
        if (
            state == null ||
            viewportWidth <= 0 ||
            viewportHeight <= 0 ||
            state.sourceWidth <= 0 ||
            state.sourceHeight <= 0 ||
            state.landmarks.size <= MAX_REQUIRED_LANDMARK_INDEX
        ) {
            activeRuntime.updateTrackingTestLip(EMPTY_FLOATS, EMPTY_SHORTS, visible = false)
            return
        }
        val predictionSeconds = state.landmarks.predictionSeconds(renderTimestampMs)
        val cameraMotionPredictionSeconds =
            state.landmarks.cameraMotionPredictionSeconds(renderTimestampMs)
        val meshSensorTimestampNs = state.sensorTimestampNs +
            (cameraMotionPredictionSeconds * NANOS_PER_SECOND).toLong()
        val gyroscopeCorrection = if (gyroscopeCorrectionEnabled) {
            gyroscopeCompensator.correctionFor(
                meshSensorTimestampNs = meshSensorTimestampNs,
                cameraSensorTimestampNs = latestCameraSensorTimestampNs,
                cameraRotationDegrees = state.rotationDegrees,
                mirrorHorizontal = state.mirrorHorizontal,
                displayRotation = surfaceView.display?.rotation ?: Surface.ROTATION_0,
                calibration = cameraCalibration,
            )
        } else {
            GyroscopeLipCompensator.Correction.NONE
        }
        val fillTransform = FillCenterTransform.calculate(
            viewWidth = viewportWidth,
            viewHeight = viewportHeight,
            sourceWidth = state.sourceWidth,
            sourceHeight = state.sourceHeight,
        )
        val imageTransform = NormalizedImageTransform(
            state.rotationDegrees,
            state.mirrorHorizontal,
        )
        writeContour(
            state,
            predictionSeconds,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.outerContour,
            outerPoints,
            gyroscopeCorrection,
        )
        writeContour(
            state,
            predictionSeconds,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.innerContour,
            innerPoints,
            gyroscopeCorrection,
        )
        val tessellated = lipTessellator.tessellate(
            outerContour = outerPoints,
            innerContour = innerPoints,
            upperProfile = ReferenceMatteLipstickProfile.upper,
            lowerProfile = ReferenceMatteLipstickProfile.lower,
        )
        var sourceIndex = 0
        var destinationIndex = 0
        while (sourceIndex < tessellated.size) {
            nativeLipVertices[destinationIndex] =
                tessellated[sourceIndex + LipMeshTessellator.X_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 1] =
                tessellated[sourceIndex + LipMeshTessellator.Y_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 2] =
                tessellated[sourceIndex + LipMeshTessellator.COVERAGE_COMPONENT_OFFSET]
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
            destinationIndex += NATIVE_VERTEX_COMPONENTS
        }
        check(
            activeRuntime.updateTrackingTestLip(
                vertices = nativeLipVertices,
                indices = lipTessellator.indices,
                visible = true,
            ),
        ) { "Native Vulkan tracking-test lip upload failed" }
    }

    private fun writeContour(
        state: LandmarkState,
        predictionSeconds: Float,
        fillTransform: FillCenterTransform,
        imageTransform: NormalizedImageTransform,
        topology: IntArray,
        output: FloatArray,
        gyroscopeCorrection: GyroscopeLipCompensator.Correction,
    ) {
        topology.forEachIndexed { pointIndex, landmarkIndex ->
            val rawX = state.landmarks.x(landmarkIndex, predictionSeconds)
            val rawY = state.landmarks.y(landmarkIndex, predictionSeconds)
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayY = imageTransform.mapY(rawX, rawY)
            val correctedX = gyroscopeCorrection.mapX(displayX, displayY)
            val correctedY = gyroscopeCorrection.mapY(displayX, displayY)
            val outputIndex = pointIndex * POINT_SIZE
            output[outputIndex] =
                fillTransform.mapX(correctedX, state.sourceWidth) / viewportWidth
            output[outputIndex + 1] =
                fillTransform.mapY(correctedY, state.sourceHeight) / viewportHeight
        }
    }

    private fun reportPresentationTiming(activeRuntime: NativeVulkanDiagnosticRuntime) {
        val sample = activeRuntime.latestPresentationSample() ?: return
        if (sample.presentationId <= lastPresentationId) return
        lastPresentationId = sample.presentationId
        presentationSampleCount++
        if (sample.cameraSensorTimestampNs == lastPresentedCameraTimestampNs) {
            reusedCameraPresentationCount++
        }
        lastPresentedCameraTimestampNs = sample.cameraSensorTimestampNs
        if (
            lastActualPresentationTimestampNs > 0L &&
            sample.actualPresentationTimestampNs > lastActualPresentationTimestampNs
        ) {
            appendTimingSample(
                actualIntervalSamplesMs,
                (sample.actualPresentationTimestampNs - lastActualPresentationTimestampNs) /
                    NANOS_PER_MILLISECOND.toFloat(),
            )
        }
        lastActualPresentationTimestampNs = sample.actualPresentationTimestampNs
        if (sample.sensorToActualMs.isFinite()) {
            appendTimingSample(sensorToActualSamplesMs, sample.sensorToActualMs)
        }
        if (presentationSampleCount == 1L || presentationSampleCount % TIMING_LOG_INTERVAL == 0L) {
            val sortedLatency = sensorToActualSamplesMs.sorted()
            val sortedIntervals = actualIntervalSamplesMs.sorted()
            Log.i(
                LOG_TAG,
                "actualPresent id=${sample.presentationId} " +
                    "sensorToActualMs=${sample.sensorToActualMs} " +
                    "sensorToActualP50Ms=${percentile(sortedLatency, 0.50f)} " +
                    "sensorToActualP95Ms=${percentile(sortedLatency, 0.95f)} " +
                    "actualIntervalP50Ms=${percentile(sortedIntervals, 0.50f)} " +
                    "actualIntervalP95Ms=${percentile(sortedIntervals, 0.95f)} " +
                    "reusedCameraFraction=" +
                    "${reusedCameraPresentationCount / presentationSampleCount.toFloat()} " +
                    "presentMarginMs=${sample.presentMarginNs / NANOS_PER_MILLISECOND.toFloat()} " +
                    "refreshMs=${sample.refreshDurationNs / NANOS_PER_MILLISECOND.toFloat()} " +
                    "telemetry=${trackingTelemetrySink != null}",
            )
        }
    }

    private fun appendTimingSample(samples: ArrayList<Float>, value: Float) {
        if (samples.size == MAX_TIMING_SAMPLES) samples.removeAt(0)
        samples += value
    }

    private fun postFrameCallback() {
        if (!resumed || frameCallbackPosted || destroyRequested || destroyed) return
        frameCallbackPosted = true
        choreographer.postFrameCallback(this)
    }

    private fun removeFrameCallback() {
        if (!frameCallbackPosted) return
        choreographer.removeFrameCallback(this)
        frameCallbackPosted = false
    }

    private fun finishDestroy() {
        if (destroyed) return
        destroyed = true
        runtime?.close()
        runtime = null
        latestCameraTransform = null
        latestLandmarks = null
        Log.i(LOG_TAG, "destroyed")
    }

    private fun reportFatalError(message: String) {
        if (fatalErrorDelivered) return
        fatalErrorDelivered = true
        pause()
        onError(message)
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Native Vulkan visible renderer must be accessed from the main thread"
        }
    }

    private data class LandmarkState(
        val landmarks: LandmarkRenderFrame,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotationDegrees: Int,
        val mirrorHorizontal: Boolean,
        val sensorTimestampNs: Long,
    )

    companion object {
        private const val LOG_TAG = "ARMakeupVulkanVisible"
        private const val POINT_SIZE = 2
        private const val NATIVE_VERTEX_COMPONENTS = 3
        private const val FULL_ROTATION = 360
        private const val NO_TIMESTAMP = -1L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val TIMING_LOG_INTERVAL = 60L
        private const val MAX_TIMING_SAMPLES = 900
        private val EMPTY_FLOATS = FloatArray(0)
        private val EMPTY_SHORTS = ShortArray(0)
        private val MAX_REQUIRED_LANDMARK_INDEX = maxOf(
            LipLandmarkTopology.outerContour.maxOrNull() ?: 0,
            LipLandmarkTopology.innerContour.maxOrNull() ?: 0,
        )

        private fun normalizeRotation(rotationDegrees: Int): Int =
            ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION

        private fun percentile(sortedValues: List<Float>, fraction: Float): Float {
            if (sortedValues.isEmpty()) return Float.NaN
            val index = kotlin.math.floor((sortedValues.lastIndex * fraction).toDouble())
                .toInt()
                .coerceIn(0, sortedValues.lastIndex)
            return sortedValues[index]
        }
    }
}
