package com.example.armakeup.render

import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.example.armakeup.R
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.makeup.LipstickOpticalProfile
import com.example.armakeup.makeup.LipstickPigmentPalette
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import com.example.armakeup.makeup.ReferenceLipstickRenderProfiles
import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.AndroidGyroscopeSource
import com.example.armakeup.tracking.CameraProjectionCalibration
import com.example.armakeup.tracking.GyroscopeLipCompensator
import com.example.armakeup.tracking.GyroscopeRotationHistory
import com.example.armakeup.tracking.LandmarkRenderFrame
import com.example.armakeup.tracking.NormalizedImageTransform
import com.example.armakeup.tracking.TemporalLandmarkRefiner
import com.example.armakeup.tracking.TrackingRenderSample
import com.example.armakeup.tracking.TrackingRenderTiming
import com.example.armakeup.tracking.TrackingTelemetrySink
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.Stream
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.ChoreographerHelper
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread-owned Filament compositor. */
internal class FilamentMakeupRenderer(
    context: Context,
    private val surfaceView: SurfaceView,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val displayHelper = DisplayHelper(context)
    private val engineSelection = FilamentEngineFactory.create(context)
    private val engine = engineSelection.engine
    private val activeBackend = engineSelection.activeBackend
    private val nativeVulkanProbe = NativeVulkanBootstrap.probe()
    private val nativeVulkanRuntime = if (
        engineSelection.requestedBackend == MakeupRenderBackend.VULKAN
    ) {
        NativeVulkanDiagnosticRuntime.createOrNull(nativeVulkanProbe)
    } else {
        null
    }
    private val filamentRenderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val frameScheduler = FrameScheduler()
    private val frameTimelineObserver = createRenderFrameTimelineObserver()
    private val materials = engineSelection.materials
    private val cameraMaterialInstance = materials.camera.createInstance()
    private val upperLipMaterialInstance = materials.lipstick.createInstance()
    private val lowerLipMaterialInstance = materials.lipstick.createInstance()
    private val productUpperPigment =
        ContextCompat.getColor(context, R.color.lipstick_matte_upper)
    private val productLowerPigment =
        ContextCompat.getColor(context, R.color.lipstick_matte_lower)
    private val trackingTestPigment =
        ContextCompat.getColor(context, R.color.lipstick_tracking_test)
    private val cameraTexture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(engine)
    private val textureSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )
    private val skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine)
    private val lipTessellator = LipMeshTessellator()
    private val materialTemporalController = LipstickMaterialTemporalController()
    private val temporalLandmarkRefiner = TemporalLandmarkRefiner()
    private val gyroscopeRotationHistory = GyroscopeRotationHistory()
    private val gyroscopeLipCompensator = GyroscopeLipCompensator(gyroscopeRotationHistory)
    private val gyroscopeSource = AndroidGyroscopeSource(
        context = context.applicationContext,
        history = gyroscopeRotationHistory,
        handler = mainHandler,
    )
    private val cameraMesh = createCameraMesh()
    private val lipMesh = createLipMesh()
    private val lipVertexUploader = DynamicVertexUploader(
        engine = engine,
        vertexBuffer = lipMesh.vertexBuffer,
        byteCount = lipTessellator.vertexCount * LIP_VERTEX_STRIDE_BYTES,
        handler = mainHandler,
    )
    private val outerPoints = FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val innerPoints = FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)
    private val displayedOuterPoints = FloatArray(outerPoints.size)
    private val displayedInnerPoints = FloatArray(innerPoints.size)

    private var swapChain: SwapChain? = null
    private var cameraInput: CameraInput? = null
    private var activeSurfaceRequest: SurfaceRequest? = null
    private var latestLandmarks: LandmarkState? = null
    private var latestCameraTransform: VulkanCameraTransform? = null
    private var cameraProjectionCalibration: CameraProjectionCalibration? = null
    private var gyroscopeCorrectionEnabled = true
    private var displayQueueProtectionEnabled = readDisplayQueueProtectionState()
    private var lipEntityVisible = false
    private var resumed = false
    private var destroyRequested = false
    private var destroyed = false
    private var fatalErrorDelivered = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lipstickFinish = LipstickFinish.SATIN
    private var trackingTelemetrySink: TrackingTelemetrySink? = null
    private var displayedMeasurementTimestampMs = NO_TIMESTAMP
    private var displayedSensorTimestampNs = NO_TIMESTAMP
    private var displayedGeometryUploadAcceptedTimestampNs = NO_TIMESTAMP
    private var displayedPredictionSeconds = 0f
    private var displayedCameraMotionPredictionSeconds = 0f
    private var displayedGlobalPredictionCoverage = 0f
    private var displayedMaterialTemporalState =
        LipstickMaterialTemporalController.State.STATIONARY
    private var displayedGyroscopeCorrection = GyroscopeLipCompensator.Correction.NONE

    @get:StringRes
    internal val renderBackendLabelRes: Int
        get() = when {
            activeBackend == MakeupRenderBackend.VULKAN -> R.string.render_backend_vulkan
            cameraInput is VulkanCameraInput ->
                R.string.render_backend_opengl_fallback_vulkan_camera
            nativeVulkanRuntime?.isReady == true ->
                R.string.render_backend_opengl_fallback_vulkan_runtime
            engineSelection.requestedBackend == MakeupRenderBackend.VULKAN ->
                R.string.render_backend_opengl_fallback
            else -> R.string.render_backend_opengl
        }

    init {
        Log.i(RENDER_LOG_TAG, engineSelection.diagnostic)
        Log.i(
            RENDER_LOG_TAG,
            "temporalFlowVisible=${temporalLandmarkRefiner.visibleApplicationEnabled} " +
                "displayQueueProtection=$displayQueueProtectionEnabled",
        )
        Log.i(NATIVE_VULKAN_LOG_TAG, nativeVulkanProbe.diagnostic)
        configureMaterialInstances()
        configureScene()
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
        frameScheduler.setRenderer(filamentRenderer)
    }

    fun onSurfaceRequested(request: SurfaceRequest) {
        ensureMainThread()
        if (destroyRequested || destroyed) {
            request.willNotProvideSurface()
            return
        }
        if (activeSurfaceRequest != null) {
            request.willNotProvideSurface()
            reportFatalError("CameraX requested overlapping preview surfaces")
            return
        }

        try {
            val input = obtainCameraInput(request.resolution.width, request.resolution.height)
            activeSurfaceRequest = request
            request.setTransformationInfoListener(mainExecutor) { info ->
                if (activeSurfaceRequest === request) {
                    applyCameraTransform(request, info)
                }
            }
            request.provideSurface(input.surface, mainExecutor) {
                if (activeSurfaceRequest === request) {
                    activeSurfaceRequest = null
                }
                if (destroyRequested) finishDestroy()
            }
        } catch (error: RuntimeException) {
            activeSurfaceRequest = null
            request.willNotProvideSurface()
            reportFatalError(error.message ?: "Failed to create camera GPU stream")
        }
    }

    fun setResult(
        landmarks: LandmarkRenderFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        sensorTimestampNs: Long,
    ) {
        ensureMainThread()
        latestLandmarks = LandmarkState(
            landmarks,
            sourceWidth,
            sourceHeight,
            rotationDegrees,
            mirrorHorizontal,
            sensorTimestampNs,
        )
    }

    fun setTrackingTelemetrySink(sink: TrackingTelemetrySink?) {
        ensureMainThread()
        val timelineObservationWasActive = trackingTelemetrySink != null
        trackingTelemetrySink = sink
        val timelineObservationIsActive = sink != null
        if (resumed && timelineObservationWasActive != timelineObservationIsActive) {
            if (timelineObservationIsActive) {
                // Re-post Filament after the observer so both callbacks describe the same frame.
                frameScheduler.remove()
                frameTimelineObserver.start()
                frameScheduler.post()
            } else {
                frameTimelineObserver.stop()
            }
        }
    }

    fun setCameraProjectionCalibration(calibration: CameraProjectionCalibration?) {
        ensureMainThread()
        cameraProjectionCalibration = calibration
        Log.i(
            GYROSCOPE_LOG_TAG,
            if (calibration == null) {
                "cameraCalibration=unavailable correctionEnabled=false"
            } else {
                "cameraCalibration=${calibration.rawFocalXNormalized}," +
                    "${calibration.rawFocalYNormalized} gyroAvailable=${gyroscopeSource.isAvailable}"
            },
        )
    }

    fun setGyroscopeCorrectionEnabled(enabled: Boolean) {
        ensureMainThread()
        if (gyroscopeCorrectionEnabled == enabled) return
        gyroscopeCorrectionEnabled = enabled
        gyroscopeRotationHistory.clear()
        if (resumed) {
            if (enabled) gyroscopeSource.start() else gyroscopeSource.stop()
        }
        Log.i(GYROSCOPE_LOG_TAG, "correctionEnabled=$enabled")
    }

    /**
     * Enables Filament's own actual-display feedback guard for a controlled debug A/B.
     *
     * The native renderer compares completed frame history with the Choreographer target and
     * lets [Renderer.beginFrame] skip a submission when the CPU/display queue has grown by at
     * least one additional refresh interval. This does not change tracker prediction or makeup
     * geometry; it only allows the compositor queue to drain.
     */
    fun setDisplayQueueProtectionEnabled(enabled: Boolean) {
        ensureMainThread()
        if (destroyRequested || destroyed) return
        val available = runCatching {
            engine.hasFeatureFlag(DISPLAY_QUEUE_PROTECTION_FEATURE)
        }.getOrDefault(false)
        val accepted = available && runCatching {
            engine.setFeatureFlag(DISPLAY_QUEUE_PROTECTION_FEATURE, enabled)
        }.getOrDefault(false)
        displayQueueProtectionEnabled = if (available) {
            runCatching {
                engine.getFeatureFlag(DISPLAY_QUEUE_PROTECTION_FEATURE)
            }.getOrDefault(false)
        } else {
            false
        }
        Log.i(
            RENDER_LOG_TAG,
            "displayQueueProtection requested=$enabled available=$available " +
                "accepted=$accepted active=$displayQueueProtectionEnabled",
        )
    }

    private fun readDisplayQueueProtectionState(): Boolean = runCatching {
        engine.hasFeatureFlag(DISPLAY_QUEUE_PROTECTION_FEATURE) &&
            engine.getFeatureFlag(DISPLAY_QUEUE_PROTECTION_FEATURE)
    }.getOrDefault(false)

    fun clear() {
        ensureMainThread()
        latestLandmarks = null
        temporalLandmarkRefiner.clear()
        gyroscopeRotationHistory.clear()
        hideLipEntity()
    }

    fun setLipstickFinish(finish: LipstickFinish) {
        ensureMainThread()
        if (lipstickFinish == finish) return
        lipstickFinish = finish
        applyLipstickRenderProfile(finish)
        Log.i(RENDER_LOG_TAG, "lipstickFinish=${finish.name}")
    }

    fun resume() {
        ensureMainThread()
        if (destroyRequested || destroyed || resumed) return
        resumed = true
        if (gyroscopeCorrectionEnabled) gyroscopeSource.start()
        nativeVulkanRuntime?.start()
        if (trackingTelemetrySink != null) frameTimelineObserver.start()
        frameScheduler.post()
    }

    fun pause() {
        ensureMainThread()
        if (!resumed) return
        resumed = false
        frameScheduler.remove()
        frameTimelineObserver.stop()
        gyroscopeSource.stop()
        nativeVulkanRuntime?.stop()
    }

    fun destroy() {
        ensureMainThread()
        if (destroyRequested || destroyed) return
        destroyRequested = true
        pause()
        uiHelper.detach()
        val request = activeSurfaceRequest
        if (request == null) {
            finishDestroy()
        } else {
            request.invalidate()
        }
    }

    private fun configureMaterialInstances() {
        listOf(
            cameraMaterialInstance,
            upperLipMaterialInstance,
            lowerLipMaterialInstance,
        ).forEach { instance ->
            instance.setParameter("cameraTexture", cameraTexture, textureSampler)
            setCameraTextureTransform(instance, IDENTITY_MATRIX)
        }
        setIlluminationSampleStep(DEFAULT_ILLUMINATION_STEP, DEFAULT_ILLUMINATION_STEP)
        setCameraDetailCoherence(FULL_CAMERA_DETAIL_COHERENCE)
        applyLipstickRenderProfile(lipstickFinish)
    }

    private fun applyLipstickRenderProfile(finish: LipstickFinish) {
        val profile = ReferenceLipstickRenderProfiles.forFinish(finish)
        val upperPigment: Int
        val lowerPigment: Int
        when (profile.pigmentPalette) {
            LipstickPigmentPalette.PRODUCT_ROSE -> {
                upperPigment = productUpperPigment
                lowerPigment = productLowerPigment
            }
            LipstickPigmentPalette.TRACKING_MAGENTA -> {
                upperPigment = trackingTestPigment
                lowerPigment = trackingTestPigment
            }
        }
        setPigmentColor(upperLipMaterialInstance, upperPigment)
        setPigmentColor(lowerLipMaterialInstance, lowerPigment)
        listOf(upperLipMaterialInstance, lowerLipMaterialInstance).forEach { material ->
            material.setParameter("coverageMultiplier", profile.coverageMultiplier)
            material.setParameter("luminancePreservation", profile.luminancePreservation)
        }
        applyLipstickOptics(profile.optics)
        applyCurrentCameraDetailCoherence()
    }

    private fun applyLipstickOptics(profile: LipstickOpticalProfile) {
        listOf(upperLipMaterialInstance, lowerLipMaterialInstance).forEach { material ->
            material.setParameter("roughness", profile.roughness)
            material.setParameter("specularStrength", profile.specularStrength)
            material.setParameter("highlightRetention", profile.highlightRetention)
            material.setParameter("microTextureRetention", profile.microTextureRetention)
            material.setParameter("wetInnerEdgeStrength", profile.wetInnerEdgeStrength)
        }
    }

    private fun setIlluminationSampleStep(horizontal: Float, vertical: Float) {
        listOf(upperLipMaterialInstance, lowerLipMaterialInstance).forEach { material ->
            material.setParameter("illuminationSampleStep", horizontal, vertical)
        }
    }

    private fun applyCurrentCameraDetailCoherence() {
        setCameraDetailCoherence(effectiveCameraDetailCoherence())
    }

    private fun effectiveCameraDetailCoherence(): Float =
        if (lipstickFinish == LipstickFinish.TRACKING_TEST) {
            FULL_CAMERA_DETAIL_COHERENCE
        } else {
            displayedMaterialTemporalState.cameraDetailCoherence
        }

    private fun setCameraDetailCoherence(coherence: Float) {
        listOf(upperLipMaterialInstance, lowerLipMaterialInstance).forEach { material ->
            material.setParameter(
                "cameraDetailCoherence",
                coherence.coerceIn(MIN_CAMERA_DETAIL_COHERENCE, FULL_CAMERA_DETAIL_COHERENCE),
            )
        }
    }

    private fun setPigmentColor(material: MaterialInstance, color: Int) {
        material.setParameter(
            "pigmentColor",
            Colors.RgbType.SRGB,
            Color.red(color) / MAX_COLOR_CHANNEL,
            Color.green(color) / MAX_COLOR_CHANNEL,
            Color.blue(color) / MAX_COLOR_CHANNEL,
        )
    }

    private fun configureScene() {
        camera.setProjection(Camera.Projection.ORTHO, -1.0, 1.0, -1.0, 1.0, 0.1, 10.0)
        camera.lookAt(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        scene.skybox = skybox
        scene.addEntity(cameraMesh.entity)
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = true
        view.isFrustumCullingEnabled = false
    }

    private fun obtainCameraInput(width: Int, height: Int): CameraInput {
        check(activeBackend == MakeupRenderBackend.OPENGL) {
            "Filament camera input is restricted to the verified OpenGL bridge"
        }
        val current = cameraInput
        if (current != null && current.width == width && current.height == height) {
            return current
        }
        current?.close()
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createVulkanCameraInputOrNull(width, height) ?: AcquiredCameraInput(width, height)
        } else {
            NativeCameraInput(width, height)
        }
        Log.i(
            RENDER_LOG_TAG,
            "cameraInput=${created.javaClass.simpleName} size=${width}x$height",
        )
        cameraInput = created
        return created
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createVulkanCameraInputOrNull(width: Int, height: Int): CameraInput? {
        val runtime = nativeVulkanRuntime?.takeIf { it.isReady } ?: return null
        return runCatching {
            VulkanCameraInput(width, height, runtime)
        }.onFailure { error ->
            runtime.closeCamera()
            Log.e(NATIVE_VULKAN_LOG_TAG, "Native camera bridge unavailable; using GL reader", error)
        }.getOrNull()
    }

    private fun applyCameraTransform(
        request: SurfaceRequest,
        info: SurfaceRequest.TransformationInfo,
    ) {
        val cropRect = info.cropRect
        val matrix = CameraTextureTransform.matrix(
            bufferWidth = request.resolution.width,
            bufferHeight = request.resolution.height,
            crop = CameraTextureTransform.CropRegion(
                cropRect.left,
                cropRect.top,
                cropRect.right,
                cropRect.bottom,
            ),
            rotationDegrees = info.rotationDegrees,
            mirrorHorizontal = info.isMirroring,
            invertDisplayHorizontally =
                cameraInput?.requiresHorizontalUvCompensation == true,
            invertDisplayVertically = cameraInput?.requiresVerticalUvCompensation == true,
        )
        latestCameraTransform = VulkanCameraTransform(
            cropLeft = cropRect.left,
            cropTop = cropRect.top,
            cropRight = cropRect.right,
            cropBottom = cropRect.bottom,
            rotationDegrees = ((info.rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION,
            mirrorHorizontal = info.isMirroring,
            matrix = matrix,
        )
        listOf(
            cameraMaterialInstance,
            upperLipMaterialInstance,
            lowerLipMaterialInstance,
        ).forEach { setCameraTextureTransform(it, matrix) }
    }

    private fun setCameraTextureTransform(material: MaterialInstance, matrix: FloatArray) {
        material.setParameter(
            "cameraTextureTransform",
            MaterialInstance.FloatElement.MAT4,
            matrix,
            0,
            1,
        )
    }

    private fun updateLipGeometry(renderTimestampMs: Long) {
        val state = latestLandmarks ?: run {
            hideLipEntity()
            return
        }
        if (
            viewportWidth <= 0 ||
            viewportHeight <= 0 ||
            state.sourceWidth <= 0 ||
            state.sourceHeight <= 0
        ) {
            return
        }
        if (state.landmarks.size <= MAX_REQUIRED_LANDMARK_INDEX) {
            hideLipEntity()
            return
        }

        val temporalCorrection = temporalLandmarkRefiner.correctionFor(
            anchorSensorTimestampNs = state.sensorTimestampNs,
            nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
        )
        val predictionSeconds = if (temporalCorrection == null) {
            state.landmarks.predictionSeconds(renderTimestampMs)
        } else {
            0f
        }
        val cameraMotionPredictionSeconds = if (temporalCorrection == null) {
            state.landmarks.cameraMotionPredictionSeconds(renderTimestampMs)
        } else {
            0f
        }
        val meshSensorTimestampNs = if (state.sensorTimestampNs == NO_TIMESTAMP) {
            NO_TIMESTAMP
        } else {
            state.sensorTimestampNs +
                (cameraMotionPredictionSeconds * NANOS_PER_SECOND).toLong()
        }
        val gyroscopeCorrection = if (!gyroscopeCorrectionEnabled || temporalCorrection != null) {
            GyroscopeLipCompensator.Correction.NONE
        } else {
            gyroscopeLipCompensator.correctionFor(
                meshSensorTimestampNs = meshSensorTimestampNs,
                cameraSensorTimestampNs =
                    cameraInput?.latestFrameSensorTimestampNs ?: NO_TIMESTAMP,
                cameraRotationDegrees = state.rotationDegrees,
                mirrorHorizontal = state.mirrorHorizontal,
                displayRotation = surfaceView.display?.rotation ?: Surface.ROTATION_0,
                calibration = cameraProjectionCalibration,
            )
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
            temporalCorrection,
            gyroscopeCorrection,
        )
        writeContour(
            state,
            predictionSeconds,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.innerContour,
            innerPoints,
            temporalCorrection,
            gyroscopeCorrection,
        )
        val tessellated = lipTessellator.tessellate(
            outerPoints,
            innerPoints,
            ReferenceMatteLipstickProfile.upper,
            ReferenceMatteLipstickProfile.lower,
        )
        val uploaded = lipVertexUploader.upload { buffer -> writeLipVertices(buffer, tessellated) }
        if (uploaded) {
            displayedGeometryUploadAcceptedTimestampNs = SystemClock.elapsedRealtimeNanos()
            displayedMaterialTemporalState = materialTemporalController.update(
                timestampMs = renderTimestampMs,
                outerPoints = outerPoints,
                innerPoints = innerPoints,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                temporalMismatchMs = materialTemporalMismatchMs(
                    state = state,
                    renderTimestampMs = renderTimestampMs,
                    cameraMotionPredictionSeconds = cameraMotionPredictionSeconds,
                ),
            )
            applyCurrentCameraDetailCoherence()
            if (trackingTelemetrySink != null) {
                outerPoints.copyInto(displayedOuterPoints)
                innerPoints.copyInto(displayedInnerPoints)
                displayedMeasurementTimestampMs = state.landmarks.measurementTimestampMs
                displayedSensorTimestampNs = state.sensorTimestampNs
                displayedPredictionSeconds = predictionSeconds
                displayedCameraMotionPredictionSeconds = cameraMotionPredictionSeconds
                displayedGlobalPredictionCoverage =
                    state.landmarks.globalPredictionCoverage()
                displayedGyroscopeCorrection = gyroscopeCorrection
            }
            showLipEntity()
        }
    }

    private fun materialTemporalMismatchMs(
        state: LandmarkState,
        renderTimestampMs: Long,
        cameraMotionPredictionSeconds: Float,
    ): Float {
        val cameraSensorTimestampNs = cameraInput?.latestFrameSensorTimestampNs ?: NO_TIMESTAMP
        if (cameraSensorTimestampNs != NO_TIMESTAMP && state.sensorTimestampNs != NO_TIMESTAMP) {
            val predictedMeshSensorTimestampNs = state.sensorTimestampNs +
                (cameraMotionPredictionSeconds * NANOS_PER_SECOND).toLong()
            return kotlin.math.abs(
                cameraSensorTimestampNs - predictedMeshSensorTimestampNs,
            ) / NANOS_PER_MILLISECOND.toFloat()
        }
        return kotlin.math.abs(
            renderTimestampMs - state.landmarks.measurementTimestampMs -
                cameraMotionPredictionSeconds * MILLIS_PER_SECOND,
        )
    }

    private fun recordDisplayedLip(
        renderTimestampMs: Long,
        frameSubmissionCpuMs: Float,
        filamentFrameRendered: Boolean,
        renderTiming: TrackingRenderTiming,
    ) {
        val sink = trackingTelemetrySink ?: return
        if (!lipEntityVisible || displayedMeasurementTimestampMs == NO_TIMESTAMP) {
            recordHiddenLip(
                renderTimestampMs,
                frameSubmissionCpuMs,
                filamentFrameRendered,
                renderTiming,
            )
            return
        }
        sink.recordRender(
            TrackingRenderSample(
                renderTimestampMs = renderTimestampMs,
                measurementTimestampMs = displayedMeasurementTimestampMs,
                sensorTimestampNs = displayedSensorTimestampNs,
                predictionSeconds = displayedPredictionSeconds,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                lipVisible = true,
                outerLipPoints = displayedOuterPoints.copyOf(),
                innerLipPoints = displayedInnerPoints.copyOf(),
                lipstickFinish = lipstickFinish.name,
                materialCameraCoherence = effectiveCameraDetailCoherence(),
                materialMotionSpeed =
                    displayedMaterialTemporalState.motionSpeedShortEdgesPerSecond,
                materialTemporalMismatchMs =
                    displayedMaterialTemporalState.temporalMismatchMs,
                frameSubmissionCpuMs = frameSubmissionCpuMs,
                filamentFrameRendered = filamentFrameRendered,
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
                displayQueueProtectionEnabled = displayQueueProtectionEnabled,
                renderTiming = renderTiming,
            ),
        )
    }

    private fun recordHiddenLip(
        renderTimestampMs: Long,
        frameSubmissionCpuMs: Float,
        filamentFrameRendered: Boolean,
        renderTiming: TrackingRenderTiming,
    ) {
        trackingTelemetrySink?.recordRender(
            TrackingRenderSample(
                renderTimestampMs = renderTimestampMs,
                measurementTimestampMs = NO_TIMESTAMP,
                sensorTimestampNs = NO_TIMESTAMP,
                predictionSeconds = 0f,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                lipVisible = false,
                outerLipPoints = FloatArray(0),
                innerLipPoints = FloatArray(0),
                lipstickFinish = lipstickFinish.name,
                materialCameraCoherence = effectiveCameraDetailCoherence(),
                materialMotionSpeed =
                    displayedMaterialTemporalState.motionSpeedShortEdgesPerSecond,
                materialTemporalMismatchMs =
                    displayedMaterialTemporalState.temporalMismatchMs,
                frameSubmissionCpuMs = frameSubmissionCpuMs,
                filamentFrameRendered = filamentFrameRendered,
                gyroscopeApplied = false,
                displayQueueProtectionEnabled = displayQueueProtectionEnabled,
                renderTiming = renderTiming.copy(
                    geometryUploadAcceptedTimestampNs = NO_TIMESTAMP,
                ),
            ),
        )
    }

    private fun writeContour(
        state: LandmarkState,
        predictionSeconds: Float,
        fillTransform: FillCenterTransform,
        imageTransform: NormalizedImageTransform,
        topology: IntArray,
        output: FloatArray,
        temporalCorrection: TemporalLandmarkRefiner.SimilarityTransform?,
        gyroscopeCorrection: GyroscopeLipCompensator.Correction,
    ) {
        topology.forEachIndexed { pointIndex, landmarkIndex ->
            val rawX = state.landmarks.x(landmarkIndex, predictionSeconds)
            val rawY = state.landmarks.y(landmarkIndex, predictionSeconds)
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayY = imageTransform.mapY(rawX, rawY)
            val correctedDisplayX: Float
            val correctedDisplayY: Float
            if (temporalCorrection == null) {
                correctedDisplayX = displayX
                correctedDisplayY = displayY
            } else {
                val displayUvY = 1f - displayY
                correctedDisplayX = temporalCorrection.mapX(displayX, displayUvY)
                correctedDisplayY = 1f - temporalCorrection.mapY(displayX, displayUvY)
            }
            val gyroCorrectedDisplayX = gyroscopeCorrection.mapX(
                correctedDisplayX,
                correctedDisplayY,
            )
            val gyroCorrectedDisplayY = gyroscopeCorrection.mapY(
                correctedDisplayX,
                correctedDisplayY,
            )
            val outputIndex = pointIndex * POINT_SIZE
            output[outputIndex] =
                fillTransform.mapX(gyroCorrectedDisplayX, state.sourceWidth) / viewportWidth
            output[outputIndex + 1] =
                fillTransform.mapY(gyroCorrectedDisplayY, state.sourceHeight) / viewportHeight
        }
    }

    private fun temporalTrackingRoi(): VulkanTemporalTrackingRoi {
        if (
            trackingTelemetrySink == null &&
            !temporalLandmarkRefiner.visibleApplicationEnabled
        ) {
            return VulkanTemporalTrackingRoi.INVALID
        }
        val state = latestLandmarks ?: return VulkanTemporalTrackingRoi.INVALID
        if (state.landmarks.size <= MAX_REQUIRED_LANDMARK_INDEX) {
            return VulkanTemporalTrackingRoi.INVALID
        }
        val predictionSeconds = state.landmarks.predictionSeconds(SystemClock.uptimeMillis())
        val imageTransform = NormalizedImageTransform(
            state.rotationDegrees,
            state.mirrorHorizontal,
        )
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        LipLandmarkTopology.outerContour.forEach { landmarkIndex ->
            val rawX = state.landmarks.x(landmarkIndex, predictionSeconds)
            val rawY = state.landmarks.y(landmarkIndex, predictionSeconds)
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayUvY = 1f - imageTransform.mapY(rawX, rawY)
            minX = minOf(minX, displayX)
            minY = minOf(minY, displayUvY)
            maxX = maxOf(maxX, displayX)
            maxY = maxOf(maxY, displayUvY)
        }
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val halfWidth = maxOf((maxX - minX) * ROI_WIDTH_SCALE, MIN_ROI_HALF_WIDTH)
        val halfHeight = maxOf(
            (maxY - minY) * ROI_HEIGHT_SCALE,
            halfWidth * ROI_ASPECT_HEIGHT,
            MIN_ROI_HALF_HEIGHT,
        )
        return VulkanTemporalTrackingRoi(
            left = (centerX - halfWidth).coerceIn(0f, 1f),
            bottom = (centerY - halfHeight).coerceIn(0f, 1f),
            right = (centerX + halfWidth).coerceIn(0f, 1f),
            top = (centerY + halfHeight).coerceIn(0f, 1f),
        ).takeIf { it.isValid } ?: VulkanTemporalTrackingRoi.INVALID
    }

    private fun writeLipVertices(buffer: ByteBuffer, tessellated: FloatArray) {
        var sourceIndex = 0
        while (sourceIndex < tessellated.size) {
            val displayX = tessellated[sourceIndex + LipMeshTessellator.X_COMPONENT_OFFSET]
            val displayY = tessellated[sourceIndex + LipMeshTessellator.Y_COMPONENT_OFFSET]
            val normalX = tessellated[sourceIndex + LipMeshTessellator.NORMAL_X_COMPONENT_OFFSET]
            val normalY = tessellated[sourceIndex + LipMeshTessellator.NORMAL_Y_COMPONENT_OFFSET]
            val normalZ = tessellated[sourceIndex + LipMeshTessellator.NORMAL_Z_COMPONENT_OFFSET]
            val coverage = tessellated[sourceIndex + LipMeshTessellator.COVERAGE_COMPONENT_OFFSET]
            val ring = tessellated[sourceIndex + LipMeshTessellator.RING_COMPONENT_OFFSET]
            val arc = tessellated[sourceIndex + LipMeshTessellator.ARC_COMPONENT_OFFSET]
            buffer.putFloat(displayX * 2f - 1f)
            buffer.putFloat(1f - displayY * 2f)
            buffer.putFloat(LIP_Z)
            buffer.putFloat(displayX)
            buffer.putFloat(1f - displayY)
            buffer.putFloat(ring)
            buffer.putFloat(arc)
            buffer.putFloat(normalX * 0.5f + 0.5f)
            buffer.putFloat(normalY * 0.5f + 0.5f)
            buffer.putFloat(normalZ * 0.5f + 0.5f)
            buffer.putFloat(coverage)
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
        }
    }

    private fun showLipEntity() {
        if (lipEntityVisible) return
        scene.addEntity(lipMesh.entity)
        lipEntityVisible = true
    }

    private fun hideLipEntity() {
        if (
            !lipEntityVisible &&
            displayedMeasurementTimestampMs == NO_TIMESTAMP &&
            displayedMaterialTemporalState.motionSpeedShortEdgesPerSecond == 0f &&
            displayedMaterialTemporalState.cameraDetailCoherence == FULL_CAMERA_DETAIL_COHERENCE
        ) {
            return
        }
        if (lipEntityVisible) {
            scene.removeEntity(lipMesh.entity)
            lipEntityVisible = false
        }
        materialTemporalController.reset()
        displayedMaterialTemporalState = LipstickMaterialTemporalController.State.STATIONARY
        applyCurrentCameraDetailCoherence()
        displayedMeasurementTimestampMs = NO_TIMESTAMP
        displayedSensorTimestampNs = NO_TIMESTAMP
        displayedPredictionSeconds = 0f
        displayedCameraMotionPredictionSeconds = 0f
        displayedGlobalPredictionCoverage = 0f
        displayedGyroscopeCorrection = GyroscopeLipCompensator.Correction.NONE
    }

    private fun createCameraMesh(): MeshResources {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(CAMERA_VERTEX_COUNT)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                CAMERA_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                POSITION_COMPONENT_COUNT * FLOAT_BYTES,
                CAMERA_VERTEX_STRIDE_BYTES,
            )
            .build(engine)
        val vertexData = nativeBuffer(CAMERA_VERTEX_COUNT * CAMERA_VERTEX_STRIDE_BYTES).apply {
            putCameraVertex(-1f, 1f, 0f, 1f)
            putCameraVertex(-1f, -1f, 0f, 0f)
            putCameraVertex(1f, -1f, 1f, 0f)
            putCameraVertex(1f, 1f, 1f, 1f)
            flip()
        }
        vertexBuffer.setBufferAt(engine, 0, vertexData)

        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        val indexBuffer = createIndexBuffer(indices)
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, CAMERA_Z, 1f, 1f, 0.1f))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                indices.size,
            )
            .material(0, cameraMaterialInstance)
            .build(engine, entity)
        return MeshResources(entity, vertexBuffer, indexBuffer)
    }

    private fun ByteBuffer.putCameraVertex(x: Float, y: Float, u: Float, v: Float) {
        putFloat(x)
        putFloat(y)
        putFloat(CAMERA_Z)
        putFloat(u)
        putFloat(v)
    }

    private fun createLipMesh(): MeshResources {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(lipTessellator.vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                POSITION_COMPONENT_COUNT * FLOAT_BYTES,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.COLOR,
                0,
                VertexBuffer.AttributeType.FLOAT4,
                (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT * 2) * FLOAT_BYTES,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV1,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT) * FLOAT_BYTES,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .build(engine)
        vertexBuffer.setBufferAt(
            engine,
            0,
            nativeBuffer(lipTessellator.vertexCount * LIP_VERTEX_STRIDE_BYTES),
        )
        val indexBuffer = createIndexBuffer(lipTessellator.indices)
        val entity = EntityManager.get().create()
        RenderableManager.Builder(2)
            .boundingBox(Box(0f, 0f, LIP_Z, 1.25f, 1.25f, 0.1f))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                lipTessellator.indicesPerLip,
            )
            .geometry(
                1,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                lipTessellator.indicesPerLip,
                lipTessellator.indicesPerLip,
            )
            .material(0, upperLipMaterialInstance)
            .material(1, lowerLipMaterialInstance)
            .build(engine, entity)
        return MeshResources(entity, vertexBuffer, indexBuffer)
    }

    private fun createIndexBuffer(indices: ShortArray): IndexBuffer {
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val data = nativeBuffer(indices.size * SHORT_BYTES)
        indices.forEach(data::putShort)
        data.flip()
        indexBuffer.setBuffer(engine, data)
        return indexBuffer
    }

    private fun nativeBuffer(byteCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())

    private fun reportFatalError(message: String) {
        if (fatalErrorDelivered) return
        fatalErrorDelivered = true
        pause()
        onError(message)
    }

    private fun finishDestroy() {
        if (destroyed) return
        destroyed = true
        hideLipEntity()
        cameraInput?.close()
        cameraInput = null
        nativeVulkanRuntime?.close()
        destroyMesh(lipMesh)
        destroyMesh(cameraMesh)
        engine.destroyMaterialInstance(lowerLipMaterialInstance)
        engine.destroyMaterialInstance(upperLipMaterialInstance)
        engine.destroyMaterialInstance(cameraMaterialInstance)
        engine.destroyMaterial(materials.lipstick)
        engine.destroyMaterial(materials.camera)
        engine.destroyTexture(cameraTexture)
        engine.destroySkybox(skybox)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroyRenderer(filamentRenderer)
        swapChain?.let(engine::destroySwapChain)
        swapChain = null
        EntityManager.get().destroy(cameraEntity)
        engine.flushAndWait()
        engine.destroy()
    }

    private fun destroyMesh(mesh: MeshResources) {
        engine.destroyEntity(mesh.entity)
        engine.destroyVertexBuffer(mesh.vertexBuffer)
        engine.destroyIndexBuffer(mesh.indexBuffer)
        EntityManager.get().destroy(mesh.entity)
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Filament compositor must be accessed from the main thread"
        }
    }

    private inner class FrameScheduler : ChoreographerHelper() {
        override fun onFrame(frameTimeNanos: Long) {
            if (!resumed || destroyRequested || destroyed || !uiHelper.isReadyToRender) return
            val frameTimeline = frameTimelineObserver.consume(frameTimeNanos)
            val traceStarted = frameTimeline != null &&
                frameTimeline.vsyncId >= 0L &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                Trace.isEnabled()
            if (traceStarted) {
                Trace.beginSection("$FRAME_TRACE_PREFIX${frameTimeline.vsyncId}")
            }
            try {
                val callbackMonotonicTimestampNs = System.nanoTime()
                val renderStartTimestampNs = SystemClock.elapsedRealtimeNanos()
                val vsyncElapsedRealtimeTimestampNs = monotonicToElapsedRealtimeTimestampNs(
                    monotonicTimestampNs = frameTimeNanos,
                    callbackMonotonicTimestampNs = callbackMonotonicTimestampNs,
                    callbackElapsedRealtimeTimestampNs = renderStartTimestampNs,
                )
                val expectedPresentationTimestampNs =
                    frameTimeline?.expectedPresentationTimeNanos?.let { timestampNs ->
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
                val renderTimestampMs = SystemClock.uptimeMillis()
                cameraInput?.pushLatestFrame()
                updateLipGeometry(renderTimestampMs)
                val currentSwapChain = swapChain
                val filamentFrameRendered = if (
                    currentSwapChain != null &&
                    filamentRenderer.beginFrame(currentSwapChain, frameTimeNanos)
                ) {
                    filamentRenderer.render(view)
                    filamentRenderer.endFrame()
                    true
                } else {
                    false
                }
                val renderSubmitTimestampNs = SystemClock.elapsedRealtimeNanos()
                val frameSubmissionCpuMs = (
                    renderSubmitTimestampNs - renderStartTimestampNs
                ) / NANOS_PER_MILLISECOND.toFloat()
                recordDisplayedLip(
                    renderTimestampMs = renderTimestampMs,
                    frameSubmissionCpuMs = frameSubmissionCpuMs,
                    filamentFrameRendered = filamentFrameRendered,
                    renderTiming = TrackingRenderTiming(
                        vsyncTimestampNs = vsyncElapsedRealtimeTimestampNs,
                        renderStartTimestampNs = renderStartTimestampNs,
                        cameraFrameSelectedTimestampNs =
                            cameraInput?.latestFrameSelectedTimestampNs ?: NO_TIMESTAMP,
                        cameraFrameSensorTimestampNs =
                            cameraInput?.latestFrameSensorTimestampNs ?: NO_TIMESTAMP,
                        geometryUploadAcceptedTimestampNs =
                            displayedGeometryUploadAcceptedTimestampNs,
                        renderSubmitTimestampNs = renderSubmitTimestampNs,
                        presentationTimestampNs = NO_TIMESTAMP,
                        frameTimelineVsyncId = frameTimeline?.vsyncId ?: NO_TIMESTAMP,
                        expectedPresentationTimestampNs = expectedPresentationTimestampNs,
                        renderDeadlineTimestampNs = renderDeadlineTimestampNs,
                    ),
                )
            } catch (error: RuntimeException) {
                reportFatalError(error.message ?: "Filament render failure")
            } finally {
                if (traceStarted) Trace.endSection()
            }
        }
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            if (destroyed) return
            swapChain?.let(engine::destroySwapChain)
            swapChain = engine.createSwapChain(surface)
            displayHelper.attach(filamentRenderer, surfaceView.display)
        }

        override fun onDetachedFromSurface() {
            if (destroyed) return
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (destroyed) return
            viewportWidth = width
            viewportHeight = height
            view.viewport = Viewport(0, 0, width, height)
            setIlluminationSampleStep(
                ILLUMINATION_SAMPLE_RADIUS_PIXELS / width.coerceAtLeast(1),
                ILLUMINATION_SAMPLE_RADIUS_PIXELS / height.coerceAtLeast(1),
            )
            FilamentHelper.synchronizePendingFrames(engine)
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

    private data class MeshResources(
        val entity: Int,
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
    )

    private interface CameraInput {
        val width: Int
        val height: Int
        val surface: Surface
        val requiresHorizontalUvCompensation: Boolean
        val requiresVerticalUvCompensation: Boolean
        val latestFrameSensorTimestampNs: Long
        val latestFrameSelectedTimestampNs: Long
        fun pushLatestFrame()
        fun close()
    }

    /** API 24–28 compatibility path. Filament NATIVE streams are copy-free but unsynchronized. */
    private inner class NativeCameraInput(
        override val width: Int,
        override val height: Int,
    ) : CameraInput {
        private val surfaceTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(width, height)
        }
        private val stream = Stream.Builder()
            .stream(surfaceTexture)
            .width(width)
            .height(height)
            .build(engine)
        override val surface: Surface = Surface(surfaceTexture)
        override val requiresHorizontalUvCompensation: Boolean = false
        override val requiresVerticalUvCompensation: Boolean = false
        override val latestFrameSensorTimestampNs: Long = NO_TIMESTAMP
        override val latestFrameSelectedTimestampNs: Long = NO_TIMESTAMP

        init {
            cameraTexture.setExternalStream(engine, stream)
        }

        override fun pushLatestFrame() = Unit

        override fun close() {
            surface.release()
            engine.destroyStream(stream)
            engine.flushAndWait()
            surfaceTexture.release()
        }
    }

    /** API 29+ OpenGL bridge with explicit HardwareBuffer acquisition and release callbacks. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class VulkanCameraInput(
        override val width: Int,
        override val height: Int,
        private val runtime: NativeVulkanDiagnosticRuntime,
    ) : CameraInput {
        private val stream = Stream.Builder()
            .width(width)
            .height(height)
            .build(engine)
        override val surface: Surface = checkNotNull(runtime.configureCamera(width, height)) {
            "Native Vulkan camera surface creation failed"
        }
        override val requiresHorizontalUvCompensation: Boolean = true
        override val requiresVerticalUvCompensation: Boolean = true
        override var latestFrameSensorTimestampNs: Long = NO_TIMESTAMP
            private set
        override var latestFrameSelectedTimestampNs: Long = NO_TIMESTAMP
            private set
        private val firstFrameLogged = AtomicBoolean(false)

        init {
            cameraTexture.setExternalStream(engine, stream)
        }

        override fun pushLatestFrame() {
            val transform = latestCameraTransform
            if (transform == null) return
            val frame = runtime.acquireCameraFrame(
                transform = transform,
                landmarkSensorTimestampNs = latestLandmarks?.sensorTimestampNs,
                trackingRoi = temporalTrackingRoi(),
            )
            if (frame == null) return
            try {
                val temporalTracking = frame.temporalTracking
                if (temporalTracking != null) {
                    temporalLandmarkRefiner.offer(
                        result = temporalTracking,
                        deliveryElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    )
                } else if (frame.temporalTrackingAttempted) {
                    temporalLandmarkRefiner.clear()
                }
                stream.setAcquiredImage(frame.hardwareBuffer, mainHandler) {
                    runtime.releaseCameraFrame(frame)
                }
                latestFrameSensorTimestampNs = frame.sensorTimestampNs
                latestFrameSelectedTimestampNs = SystemClock.elapsedRealtimeNanos()
                if (firstFrameLogged.compareAndSet(false, true)) {
                    val landmarkAgeMs = frame.landmarkAgeNs?.div(NANOS_PER_MILLISECOND)
                    Log.i(
                        NATIVE_VULKAN_LOG_TAG,
                        "cameraFrame token=${frame.nativeToken} " +
                            "timestampNs=${frame.sensorTimestampNs} size=${frame.width}x${frame.height} " +
                            "format=${frame.hardwareBufferFormat} usage=${frame.hardwareBufferUsage} " +
                            "acquireFence=${frame.acquireFenceImported} " +
                            "releaseFence=${frame.releaseFenceExported} landmarkAgeMs=$landmarkAgeMs",
                    )
                }
            } catch (error: RuntimeException) {
                runtime.releaseCameraFrame(frame)
                throw error
            }
        }

        override fun close() {
            engine.destroyStream(stream)
            engine.flushAndWait()
            surface.release()
            runtime.closeCamera()
        }
    }

    /** API 29+ fallback when the native AImageReader/importer is unavailable. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class AcquiredCameraInput(
        override val width: Int,
        override val height: Int,
    ) : CameraInput {
        private val imageReader = ImageReader.newInstance(
            width,
            height,
            ImageFormat.PRIVATE,
            ACQUIRED_MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        private val stream = Stream.Builder()
            .width(width)
            .height(height)
            .build(engine)
        override val surface: Surface = imageReader.surface
        override val requiresHorizontalUvCompensation: Boolean =
            activeBackend == MakeupRenderBackend.OPENGL
        override val requiresVerticalUvCompensation: Boolean =
            activeBackend == MakeupRenderBackend.OPENGL
        override var latestFrameSensorTimestampNs: Long = NO_TIMESTAMP
            private set
        override var latestFrameSelectedTimestampNs: Long = NO_TIMESTAMP
            private set

        init {
            cameraTexture.setExternalStream(engine, stream)
        }

        override fun pushLatestFrame() {
            val image = try {
                imageReader.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            val hardwareBuffer = image.hardwareBuffer
            if (hardwareBuffer == null) {
                image.close()
                return
            }
            stream.setAcquiredImage(hardwareBuffer, mainHandler) {
                image.close()
            }
            latestFrameSensorTimestampNs = image.timestamp
            latestFrameSelectedTimestampNs = SystemClock.elapsedRealtimeNanos()
        }

        override fun close() {
            engine.destroyStream(stream)
            engine.flushAndWait()
            imageReader.close()
        }
    }

    private class DynamicVertexUploader(
        private val engine: Engine,
        private val vertexBuffer: VertexBuffer,
        byteCount: Int,
        private val handler: Handler,
    ) {
        private val slots = Array(UPLOAD_BUFFER_COUNT) {
            UploadSlot(ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()))
        }

        fun upload(write: (ByteBuffer) -> Unit): Boolean {
            val slot = slots.firstOrNull { !it.inUse } ?: return false
            slot.inUse = true
            slot.buffer.clear()
            write(slot.buffer)
            slot.buffer.flip()
            vertexBuffer.setBufferAt(
                engine,
                0,
                slot.buffer,
                0,
                slot.buffer.remaining(),
                handler,
            ) {
                slot.inUse = false
            }
            return true
        }

        private data class UploadSlot(
            val buffer: ByteBuffer,
            var inUse: Boolean = false,
        )

        companion object {
            private const val UPLOAD_BUFFER_COUNT = 3
        }
    }

    companion object {
        private const val FLOAT_BYTES = 4
        private const val SHORT_BYTES = 2
        private const val POSITION_COMPONENT_COUNT = 3
        private const val UV_COMPONENT_COUNT = 2
        private const val COLOR_COMPONENT_COUNT = 4
        private const val CAMERA_VERTEX_COUNT = 4
        private const val CAMERA_VERTEX_STRIDE_BYTES =
            (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT) * FLOAT_BYTES
        private const val LIP_VERTEX_STRIDE_BYTES =
            (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT * 2 + COLOR_COMPONENT_COUNT) * FLOAT_BYTES
        private const val POINT_SIZE = 2
        private const val CAMERA_Z = 0f
        private const val LIP_Z = 0.05f
        private const val MAX_ALPHA = 255f
        private const val MAX_COLOR_CHANNEL = 255f
        private const val ACQUIRED_MAX_IMAGES = 4
        private const val FULL_ROTATION = 360
        private const val NO_TIMESTAMP = -1L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val MIN_CAMERA_DETAIL_COHERENCE = 0f
        private const val FULL_CAMERA_DETAIL_COHERENCE = 1f
        private const val ROI_WIDTH_SCALE = 1.35f
        private const val ROI_HEIGHT_SCALE = 2.2f
        private const val ROI_ASPECT_HEIGHT = 0.75f
        private const val MIN_ROI_HALF_WIDTH = 0.12f
        private const val MIN_ROI_HALF_HEIGHT = 0.10f
        private const val ILLUMINATION_SAMPLE_RADIUS_PIXELS = 14f
        private const val DEFAULT_ILLUMINATION_STEP = 0.01f
        private const val RENDER_LOG_TAG = "ARMakeupRender"
        private const val NATIVE_VULKAN_LOG_TAG = "ARMakeupVulkan"
        private const val GYROSCOPE_LOG_TAG = "ARMakeupGyroV6"
        private const val FRAME_TRACE_PREFIX = "ARMK_FRAME:"
        private const val DISPLAY_QUEUE_PROTECTION_FEATURE =
            "engine.skip_frame_when_cpu_ahead_of_display"
        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        private val MAX_REQUIRED_LANDMARK_INDEX = maxOf(
            LipLandmarkTopology.outerContour.max(),
            LipLandmarkTopology.innerContour.max(),
        )
    }
}

internal fun monotonicToElapsedRealtimeTimestampNs(
    monotonicTimestampNs: Long,
    callbackMonotonicTimestampNs: Long,
    callbackElapsedRealtimeTimestampNs: Long,
): Long {
    if (monotonicTimestampNs < 0L) return -1L
    return callbackElapsedRealtimeTimestampNs -
        (callbackMonotonicTimestampNs - monotonicTimestampNs)
}
