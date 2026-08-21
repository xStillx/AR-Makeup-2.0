package com.example.armakeup.render

import android.content.Context
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
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
import com.example.armakeup.tracking.TrackingRenderBackend
import com.example.armakeup.tracking.TrackingRenderSample
import com.example.armakeup.tracking.TrackingRenderTiming
import com.example.armakeup.tracking.TrackingGeometryExtractor
import com.example.armakeup.tracking.TemporalLandmarkRefiner

/** Debug-only visible swapchain proof. Filament is not constructed while this renderer is active. */
internal class NativeVulkanVisibleRenderer(
    context: Context,
    private val surfaceView: SurfaceView,
    private val sameFrameFlowVisibleEnabled: Boolean,
    private val presentOnCameraFramesOnly: Boolean,
    private val onError: (String) -> Unit,
) : MakeupRendererController, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val choreographer = Choreographer.getInstance()
    private val frameTimelineObserver = createRenderFrameTimelineObserver()
    private val presentationTelemetry = NativeVulkanPresentationTelemetry()
    private val nativeProbe = NativeVulkanBootstrap.probe()
    private val lipTessellator = LipMeshTessellator()
    private val outerPoints = FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val innerPoints = FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)
    private val flowBaseOuterPoints =
        FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val flowBaseInnerPoints =
        FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)
    private val faceAnchorPoints =
        FloatArray(TrackingGeometryExtractor.stableAnchorIndices.size * POINT_SIZE)
    private val nativeLipVertices = FloatArray(lipTessellator.vertexCount * NATIVE_VERTEX_COMPONENTS)
    private val displayToScreen = FloatArray(DISPLAY_TO_SCREEN_COMPONENTS)
    private val lipContourStabilizer = FaceAnchoredLipContourStabilizer()
    private val temporalLandmarkRefiner = TemporalLandmarkRefiner()
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
    private var retainedFlowBaseTimestampNs = NO_TIMESTAMP
    private var latestCameraSelectedTimestampNs = NO_TIMESTAMP
    private var trackingTelemetrySink: TrackingTelemetrySink? = null
    private var gyroscopeCorrectionEnabled = false
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
    private var displayedMeasurementTimestampMs = NO_TIMESTAMP
    private var displayedSensorTimestampNs = NO_TIMESTAMP
    private var displayedPredictionSeconds = 0f
    private var displayedCameraMotionPredictionSeconds = Float.NaN
    private var displayedGlobalPredictionCoverage = Float.NaN
    private var displayedGyroscopeCorrection = GyroscopeLipCompensator.Correction.NONE
    private var displayedGeometryUploadAcceptedTimestampNs = NO_TIMESTAMP
    private var displayedLipVisible = false

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
        val previousSink = trackingTelemetrySink
        if (previousSink != null && previousSink !== sink) {
            flushPresentationTelemetry(previousSink)
        }
        val timelineObservationWasActive = previousSink != null
        trackingTelemetrySink = sink
        val timelineObservationIsActive = sink != null
        if (resumed && timelineObservationWasActive != timelineObservationIsActive) {
            if (timelineObservationIsActive) {
                removeFrameCallback()
                frameTimelineObserver.start()
                postFrameCallback()
            } else {
                frameTimelineObserver.stop()
            }
        }
    }

    override fun setCameraProjectionCalibration(calibration: CameraProjectionCalibration?) {
        ensureMainThread()
        cameraCalibration = calibration
    }

    override fun setGyroscopeCorrectionEnabled(enabled: Boolean) {
        ensureMainThread()
        // The A/B device run showed no alignment improvement from the current 2D gyro mapping.
        // Keep it out of the visible display-time candidate until it can be fused with 3D pose.
        if (enabled) Log.i(LOG_TAG, "gyro request ignored by display-time tracking candidate")
        if (!gyroscopeCorrectionEnabled) return
        gyroscopeCorrectionEnabled = false
        gyroscopeHistory.clear()
        gyroscopeSource.stop()
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
        lipContourStabilizer.reset()
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
        if (trackingTelemetrySink != null) frameTimelineObserver.start()
        postFrameCallback()
    }

    override fun pause() {
        ensureMainThread()
        if (!resumed) return
        resumed = false
        removeFrameCallback()
        frameTimelineObserver.stop()
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
                val frameTimeline = frameTimelineObserver.consume(frameTimeNanos)
                val callbackMonotonicTimestampNs = System.nanoTime()
                val renderStartTimestampNs = SystemClock.elapsedRealtimeNanos()
                val vsyncElapsedRealtimeTimestampNs = monotonicToElapsedRealtimeTimestampNs(
                    monotonicTimestampNs = frameTimeNanos,
                    callbackMonotonicTimestampNs = callbackMonotonicTimestampNs,
                    callbackElapsedRealtimeTimestampNs = renderStartTimestampNs,
                )
                val expectedPresentationTimestampNs = frameTimeline
                    ?.expectedPresentationTimeNanos
                    ?.let { timestampNs ->
                        monotonicToElapsedRealtimeTimestampNs(
                            monotonicTimestampNs = timestampNs,
                            callbackMonotonicTimestampNs = callbackMonotonicTimestampNs,
                            callbackElapsedRealtimeTimestampNs = renderStartTimestampNs,
                        )
                    } ?: NO_TIMESTAMP
                val renderDeadlineTimestampNs = frameTimeline?.deadlineNanos?.let { timestampNs ->
                    monotonicToElapsedRealtimeTimestampNs(
                        monotonicTimestampNs = timestampNs,
                        callbackMonotonicTimestampNs = callbackMonotonicTimestampNs,
                        callbackElapsedRealtimeTimestampNs = renderStartTimestampNs,
                    )
                } ?: NO_TIMESTAMP
                // Preserve the pre-telemetry geometry timestamp contract: predictor extrapolation
                // remains anchored to this vsync, not to the later callback execution time.
                val renderTimestampMs = frameTimeNanos / NANOS_PER_MILLISECOND
                val cameraUpdate = activeRuntime.updateVisibleCamera(
                    transform = transform,
                    trackingRoi = temporalTrackingRoi(),
                )
                cameraUpdate.temporalTracking?.let { temporalTracking ->
                    temporalLandmarkRefiner.offer(
                        result = temporalTracking,
                        deliveryElapsedRealtimeNs = renderStartTimestampNs,
                    )
                }
                val retainedCameraTimestampNs = cameraUpdate.sensorTimestampNs
                if (retainedCameraTimestampNs > 0L) {
                    val previousCameraTimestampNs = latestCameraSensorTimestampNs
                    val cameraFrameChanged = retainedCameraTimestampNs != previousCameraTimestampNs
                    if (cameraFrameChanged) {
                        latestCameraSelectedTimestampNs = renderStartTimestampNs
                        retainedFlowBaseTimestampNs = previousCameraTimestampNs
                    }
                    latestCameraSensorTimestampNs = retainedCameraTimestampNs
                    if (cameraFrameChanged) {
                        updateTrackingTestLip(
                            activeRuntime,
                            renderTimestampMs,
                        )
                    }
                    if (!presentOnCameraFramesOnly || cameraFrameChanged) {
                        val presentationId = activeRuntime.presentVisibleFrame()
                        check(presentationId >= 0L) {
                            "Native Vulkan retained-camera present failed"
                        }
                        val renderSubmitTimestampNs = SystemClock.elapsedRealtimeNanos()
                        recordSubmittedFrame(
                            presentationId = presentationId,
                            renderTimestampMs = renderTimestampMs,
                            frameSubmissionCpuMs = (
                                renderSubmitTimestampNs - renderStartTimestampNs
                            ) / NANOS_PER_MILLISECOND.toFloat(),
                            renderTiming = TrackingRenderTiming(
                                vsyncTimestampNs = vsyncElapsedRealtimeTimestampNs,
                                renderStartTimestampNs = renderStartTimestampNs,
                                cameraFrameSelectedTimestampNs = latestCameraSelectedTimestampNs,
                                cameraFrameSensorTimestampNs = latestCameraSensorTimestampNs,
                                geometryUploadAcceptedTimestampNs =
                                    displayedGeometryUploadAcceptedTimestampNs,
                                renderSubmitTimestampNs = renderSubmitTimestampNs,
                                presentationTimestampNs = NO_TIMESTAMP,
                                frameTimelineVsyncId = frameTimeline?.vsyncId ?: NO_TIMESTAMP,
                                expectedPresentationTimestampNs = expectedPresentationTimestampNs,
                                renderDeadlineTimestampNs = renderDeadlineTimestampNs,
                            ),
                        )
                    }
                }
                trackingTelemetrySink?.let { sink ->
                    drainPresentationTelemetry(activeRuntime, sink)
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
            lipContourStabilizer.reset()
            check(activeRuntime.updateTrackingTestLip(EMPTY_FLOATS, EMPTY_SHORTS, visible = false)) {
                "Native Vulkan hidden tracking-test lip upload failed"
            }
            if (trackingTelemetrySink != null) {
                displayedMeasurementTimestampMs = NO_TIMESTAMP
                displayedSensorTimestampNs = NO_TIMESTAMP
                displayedPredictionSeconds = 0f
                displayedCameraMotionPredictionSeconds = Float.NaN
                displayedGlobalPredictionCoverage = Float.NaN
                displayedGyroscopeCorrection = GyroscopeLipCompensator.Correction.NONE
                displayedGeometryUploadAcceptedTimestampNs = NO_TIMESTAMP
                displayedLipVisible = false
            }
            return
        }
        val cameraAlignedPredictionSeconds = state.landmarks.predictionSecondsForCameraFrame(
            measurementSensorTimestampNs = state.sensorTimestampNs,
            cameraSensorTimestampNs = latestCameraSensorTimestampNs,
        )
        val flowBasePredictionSeconds = state.landmarks.predictionSecondsForCameraFrame(
            measurementSensorTimestampNs = state.sensorTimestampNs,
            cameraSensorTimestampNs = retainedFlowBaseTimestampNs.takeIf { it > 0L }
                ?: latestCameraSensorTimestampNs,
        )
        val gyroscopeCorrection = GyroscopeLipCompensator.Correction.NONE
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
            cameraAlignedPredictionSeconds,
            0f,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.outerContour,
            outerPoints,
            gyroscopeCorrection,
        )
        writeDisplayUvContour(
            state = state,
            predictionSeconds = flowBasePredictionSeconds,
            imageTransform = imageTransform,
            topology = LipLandmarkTopology.outerContour,
            output = flowBaseOuterPoints,
        )
        writeDisplayUvContour(
            state = state,
            predictionSeconds = flowBasePredictionSeconds,
            imageTransform = imageTransform,
            topology = LipLandmarkTopology.innerContour,
            output = flowBaseInnerPoints,
        )
        writeContour(
            state,
            cameraAlignedPredictionSeconds,
            0f,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.innerContour,
            innerPoints,
            gyroscopeCorrection,
        )
        writeContour(
            state,
            cameraAlignedPredictionSeconds,
            0f,
            fillTransform,
            imageTransform,
            TrackingGeometryExtractor.stableAnchorIndices,
            faceAnchorPoints,
            gyroscopeCorrection,
        )
        lipContourStabilizer.stabilize(
            anchors = faceAnchorPoints,
            outerContour = outerPoints,
            innerContour = innerPoints,
            timestampMs = latestCameraSensorTimestampNs / NANOS_PER_MILLISECOND,
        )
        val tessellated = lipTessellator.tessellate(
            outerContour = outerPoints,
            innerContour = innerPoints,
            upperProfile = ReferenceMatteLipstickProfile.upper,
            lowerProfile = ReferenceMatteLipstickProfile.lower,
        )
        val flowBaseTessellated = lipTessellator.tessellate(
            outerContour = flowBaseOuterPoints,
            innerContour = flowBaseInnerPoints,
            upperProfile = ReferenceMatteLipstickProfile.upper,
            lowerProfile = ReferenceMatteLipstickProfile.lower,
        )
        check(flowBaseTessellated.size == tessellated.size)
        var sourceIndex = 0
        var destinationIndex = 0
        while (sourceIndex < tessellated.size) {
            nativeLipVertices[destinationIndex] =
                tessellated[sourceIndex + LipMeshTessellator.X_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 1] =
                tessellated[sourceIndex + LipMeshTessellator.Y_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 2] =
                flowBaseTessellated[sourceIndex + LipMeshTessellator.X_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 3] =
                flowBaseTessellated[sourceIndex + LipMeshTessellator.Y_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 4] =
                tessellated[sourceIndex + LipMeshTessellator.COVERAGE_COMPONENT_OFFSET]
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
            destinationIndex += NATIVE_VERTEX_COMPONENTS
        }
        displayToScreen[0] = state.sourceWidth * fillTransform.scale / viewportWidth
        displayToScreen[1] = state.sourceHeight * fillTransform.scale / viewportHeight
        displayToScreen[2] = fillTransform.offsetX / viewportWidth
        displayToScreen[3] = fillTransform.offsetY / viewportHeight
        check(
            activeRuntime.updateTrackingTestLip(
                vertices = nativeLipVertices,
                indices = lipTessellator.indices,
                displayToScreen = displayToScreen,
                temporalFlowEnabled = sameFrameFlowVisibleEnabled,
                visible = true,
            ),
        ) { "Native Vulkan tracking-test lip upload failed" }
        if (trackingTelemetrySink != null) {
            displayedMeasurementTimestampMs = state.landmarks.measurementTimestampMs
            displayedSensorTimestampNs = state.sensorTimestampNs
            displayedPredictionSeconds = cameraAlignedPredictionSeconds
            displayedCameraMotionPredictionSeconds = cameraAlignedPredictionSeconds *
                state.landmarks.globalPredictionCoverage()
            displayedGlobalPredictionCoverage = state.landmarks.globalPredictionCoverage()
            displayedGyroscopeCorrection = gyroscopeCorrection
            displayedGeometryUploadAcceptedTimestampNs = SystemClock.elapsedRealtimeNanos()
            displayedLipVisible = true
        }
    }

    private fun writeDisplayUvContour(
        state: LandmarkState,
        predictionSeconds: Float,
        imageTransform: NormalizedImageTransform,
        topology: IntArray,
        output: FloatArray,
    ) {
        topology.forEachIndexed { pointIndex, landmarkIndex ->
            val rawX = state.landmarks.x(landmarkIndex, predictionSeconds)
            val rawY = state.landmarks.y(landmarkIndex, predictionSeconds)
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayY = imageTransform.mapY(rawX, rawY)
            val outputIndex = pointIndex * POINT_SIZE
            output[outputIndex] = displayX
            output[outputIndex + 1] = displayY
        }
    }

    private fun writeContour(
        state: LandmarkState,
        predictionSeconds: Float,
        additionalPredictionSeconds: Float,
        fillTransform: FillCenterTransform,
        imageTransform: NormalizedImageTransform,
        topology: IntArray,
        output: FloatArray,
        gyroscopeCorrection: GyroscopeLipCompensator.Correction,
    ) {
        topology.forEachIndexed { pointIndex, landmarkIndex ->
            val rawX = state.landmarks.xWithAdditionalPrediction(
                landmarkIndex,
                predictionSeconds,
                additionalPredictionSeconds,
            )
            val rawY = state.landmarks.yWithAdditionalPrediction(
                landmarkIndex,
                predictionSeconds,
                additionalPredictionSeconds,
            )
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

    /**
     * Full rigid-face ROI for shadow optical flow. Lip-only texture is too weak and deformable;
     * forehead, eyes, nose and cheeks provide the stable image gradients needed for global pose.
     */
    private fun temporalTrackingRoi(): VulkanTemporalTrackingRoi {
        val state = latestLandmarks ?: return VulkanTemporalTrackingRoi.INVALID
        if (state.landmarks.size <= MAX_REQUIRED_LANDMARK_INDEX) {
            return VulkanTemporalTrackingRoi.INVALID
        }
        val predictionSeconds = state.landmarks.predictionSecondsForCameraFrame(
            measurementSensorTimestampNs = state.sensorTimestampNs,
            cameraSensorTimestampNs = latestCameraSensorTimestampNs,
        )
        val imageTransform = NormalizedImageTransform(
            state.rotationDegrees,
            state.mirrorHorizontal,
        )
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        TrackingGeometryExtractor.stableAnchorIndices.forEach { landmarkIndex ->
            val rawX = state.landmarks.x(landmarkIndex, predictionSeconds)
            val rawY = state.landmarks.y(landmarkIndex, predictionSeconds)
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayUvY = imageTransform.mapY(rawX, rawY)
            minX = minOf(minX, displayX)
            minY = minOf(minY, displayUvY)
            maxX = maxOf(maxX, displayX)
            maxY = maxOf(maxY, displayUvY)
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
            return VulkanTemporalTrackingRoi.INVALID
        }
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val halfWidth = maxOf(
            (maxX - minX) * TEMPORAL_ROI_SCALE * 0.5f,
            MIN_TEMPORAL_ROI_HALF_WIDTH,
        )
        val halfHeight = maxOf(
            (maxY - minY) * TEMPORAL_ROI_SCALE * 0.5f,
            MIN_TEMPORAL_ROI_HALF_HEIGHT,
        )
        return VulkanTemporalTrackingRoi(
            left = (centerX - halfWidth).coerceIn(0f, 1f),
            top = (centerY - halfHeight).coerceIn(0f, 1f),
            right = (centerX + halfWidth).coerceIn(0f, 1f),
            bottom = (centerY + halfHeight).coerceIn(0f, 1f),
        ).takeIf { it.isValid } ?: VulkanTemporalTrackingRoi.INVALID
    }

    private fun recordSubmittedFrame(
        presentationId: Long,
        renderTimestampMs: Long,
        frameSubmissionCpuMs: Float,
        renderTiming: TrackingRenderTiming,
    ) {
        val sink = trackingTelemetrySink ?: return
        val sample = TrackingRenderSample(
            renderTimestampMs = renderTimestampMs,
            measurementTimestampMs = displayedMeasurementTimestampMs,
            sensorTimestampNs = displayedSensorTimestampNs,
            predictionSeconds = displayedPredictionSeconds,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            lipVisible = displayedLipVisible,
            outerLipPoints = if (displayedLipVisible) outerPoints.copyOf() else EMPTY_FLOATS,
            innerLipPoints = if (displayedLipVisible) innerPoints.copyOf() else EMPTY_FLOATS,
            lipstickFinish = LipstickFinish.TRACKING_TEST.name,
            frameSubmissionCpuMs = frameSubmissionCpuMs,
            filamentFrameRendered = true,
            gyroscopeApplied = displayedGyroscopeCorrection.applied,
            gyroscopeIntervalMs = displayedGyroscopeCorrection.intervalMs,
            gyroscopeRotationX = displayedGyroscopeCorrection.rotationX,
            gyroscopeRotationY = displayedGyroscopeCorrection.rotationY,
            gyroscopeRotationZ = displayedGyroscopeCorrection.rotationZ,
            gyroscopeTranslationX = displayedGyroscopeCorrection.translationX,
            gyroscopeTranslationY = displayedGyroscopeCorrection.translationY,
            gyroscopeRollRadians = displayedGyroscopeCorrection.rollRadians,
            cameraMotionPredictionSeconds = displayedCameraMotionPredictionSeconds,
            globalPredictionCoverage = displayedGlobalPredictionCoverage,
            displayQueueProtectionEnabled = false,
            filamentPresentationHintsEnabled = false,
            renderTiming = renderTiming,
            renderBackend = TrackingRenderBackend.NATIVE_VULKAN,
            presentationId = presentationId,
        )
        if (presentationId > 0L) {
            presentationTelemetry.add(sample)?.let(sink::recordRender)
        } else {
            sink.recordRender(sample)
        }
    }

    private fun drainPresentationTelemetry(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        sink: TrackingTelemetrySink?,
    ) {
        activeRuntime.drainPresentationSamples().forEach { presentation ->
            val resolved = presentationTelemetry.resolve(presentation)
            if (resolved != null && sink != null) sink.recordRender(resolved)
        }
    }

    private fun flushPresentationTelemetry(sink: TrackingTelemetrySink) {
        runtime?.let { drainPresentationTelemetry(it, sink) }
        presentationTelemetry.drainUnresolved().forEach(sink::recordRender)
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
        trackingTelemetrySink?.let(::flushPresentationTelemetry)
        trackingTelemetrySink = null
        frameTimelineObserver.stop()
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
        private const val NATIVE_VERTEX_COMPONENTS = 5
        private const val DISPLAY_TO_SCREEN_COMPONENTS = 4
        private const val FULL_ROTATION = 360
        private const val NO_TIMESTAMP = -1L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val TEMPORAL_ROI_SCALE = 1.18f
        private const val MIN_TEMPORAL_ROI_HALF_WIDTH = 0.16f
        private const val MIN_TEMPORAL_ROI_HALF_HEIGHT = 0.20f
        private const val TIMING_LOG_INTERVAL = 60L
        private const val MAX_TIMING_SAMPLES = 900
        private val EMPTY_FLOATS = FloatArray(0)
        private val EMPTY_SHORTS = ShortArray(0)
        private val MAX_REQUIRED_LANDMARK_INDEX = maxOf(
            LipLandmarkTopology.outerContour.maxOrNull() ?: 0,
            LipLandmarkTopology.innerContour.maxOrNull() ?: 0,
            TrackingGeometryExtractor.stableAnchorIndices.maxOrNull() ?: 0,
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
