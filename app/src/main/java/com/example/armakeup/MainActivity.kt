package com.example.armakeup

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.example.armakeup.databinding.ActivityMainBinding
import com.example.armakeup.arcore.ArCoreFaceAnchorActivity
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.tracking.FaceLandmarkerTracker
import com.example.armakeup.tracking.CameraCaptureMetadataStore
import com.example.armakeup.tracking.CameraProjectionCalibration
import com.example.armakeup.tracking.TrackingTelemetryRecorder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), FaceLandmarkerTracker.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var faceTracker: FaceLandmarkerTracker? = null
    private var trackerReady = false
    private var trackerInitializationStarted = false
    private var cameraStarted = false
    private var cameraBindingStarted = false
    private var cameraFpsLabel = "auto"
    private var cameraExposureCompensationIndex = 0
    private var landmarkPredictionEnabled = true
    private var trackingTelemetryRecorder: TrackingTelemetryRecorder? = null
    private val cameraCaptureMetadataStore = CameraCaptureMetadataStore()
    private val fpsMeter = FpsMeter()
    private val stopTrackingTelemetry = Runnable { stopTrackingTelemetryRecording() }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.permissionPanel.visibility = View.GONE
                startPipeline()
            } else {
                showPermissionPanel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent.getBooleanExtra(EXTRA_ENABLE_ARCORE_FACE_ANCHOR_PROOF, false)) {
            startActivity(
                Intent(this, ArCoreFaceAnchorActivity::class.java).apply {
                    putExtra(
                        EXTRA_ENABLE_NATIVE_VULKAN_VISIBLE,
                        intent.getBooleanExtra(EXTRA_ENABLE_NATIVE_VULKAN_VISIBLE, false),
                    )
                },
            )
            finish()
            return
        }
        val displayQueueProtectionOverride = if (
            debuggable && intent.hasExtra(EXTRA_ENABLE_DISPLAY_QUEUE_PROTECTION)
        ) {
            intent.getBooleanExtra(EXTRA_ENABLE_DISPLAY_QUEUE_PROTECTION, false)
        } else {
            null
        }
        val filamentPresentationHintsEnabled = !debuggable ||
            !intent.getBooleanExtra(EXTRA_DISABLE_FILAMENT_PRESENTATION_HINTS, false)
        val nativeVulkanVisibleEnabled = debuggable &&
            intent.getBooleanExtra(EXTRA_ENABLE_NATIVE_VULKAN_VISIBLE, false)
        val vulkanSameFrameFlowVisibleEnabled = debuggable && nativeVulkanVisibleEnabled &&
            intent.getBooleanExtra(EXTRA_ENABLE_VULKAN_SAME_FRAME_FLOW_VISIBLE, false)
        val nativeVulkanPresentOnCameraFramesOnly = debuggable && nativeVulkanVisibleEnabled &&
            intent.getBooleanExtra(EXTRA_NATIVE_VULKAN_PRESENT_ON_CAMERA_FRAMES_ONLY, false)
        landmarkPredictionEnabled = !debuggable ||
            !intent.getBooleanExtra(EXTRA_DISABLE_LANDMARK_PREDICTOR, false)
        cameraExposureCompensationIndex = if (debuggable) {
            intent.getIntExtra(EXTRA_CAMERA_EXPOSURE_COMPENSATION_INDEX, 0)
        } else {
            0
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.keepScreenOn = true
        binding.makeupRenderer.setErrorListener(::showFatalRendererState)
        binding.makeupRenderer.initializeRenderer(
            displayQueueProtectionEnabled = displayQueueProtectionOverride,
            filamentPresentationHintsEnabled = filamentPresentationHintsEnabled,
            nativeVulkanVisibleEnabled = nativeVulkanVisibleEnabled,
            vulkanSameFrameFlowVisibleEnabled = vulkanSameFrameFlowVisibleEnabled,
            nativeVulkanPresentOnCameraFramesOnly = nativeVulkanPresentOnCameraFramesOnly,
        )
        binding.makeupRenderer.setGyroscopeCorrectionEnabled(
            !intent.getBooleanExtra(EXTRA_DISABLE_GYROSCOPE_CORRECTION, false),
        )
        binding.finishTrackingTest.visibility = if (debuggable) View.VISIBLE else View.GONE
        trackingTelemetryRecorder = TrackingTelemetryRecorder.createIfRequested(
            context = applicationContext,
            intent = intent,
            debuggable = debuggable,
        )
        trackingTelemetryRecorder?.let { recorder ->
            binding.makeupRenderer.setTrackingTelemetrySink(recorder)
            binding.root.postDelayed(
                stopTrackingTelemetry,
                recorder.requestedWarmupMs + recorder.requestedDurationMs,
            )
        }
        binding.finishToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val finish = when (checkedId) {
                R.id.finish_matte -> LipstickFinish.MATTE
                R.id.finish_gloss -> LipstickFinish.GLOSS
                R.id.finish_tracking_test -> LipstickFinish.TRACKING_TEST
                else -> LipstickFinish.SATIN
            }
            binding.makeupRenderer.setLipstickFinish(finish)
        }

        cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ar-makeup-camera-ml")
        }

        applyWindowInsets()
        binding.permissionButton.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (hasCameraPermission()) {
            startPipeline()
        } else {
            showPermissionPanel()
        }
    }

    private fun applyWindowInsets() {
        val statusBaseMargin = binding.statusCard.marginTop
        val phaseBaseMargin = binding.phaseCard.marginBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusCard.updateVerticalMargin(top = statusBaseMargin + bars.top)
            binding.phaseCard.updateVerticalMargin(bottom = phaseBaseMargin + bars.bottom)
            insets
        }
    }

    private fun View.updateVerticalMargin(top: Int? = null, bottom: Int? = null) {
        val params = layoutParams as ViewGroup.MarginLayoutParams
        top?.let { params.topMargin = it }
        bottom?.let { params.bottomMargin = it }
        layoutParams = params
    }

    private val View.marginTop: Int
        get() = (layoutParams as ViewGroup.MarginLayoutParams).topMargin

    private val View.marginBottom: Int
        get() = (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel() {
        binding.permissionPanel.visibility = View.VISIBLE
        binding.statusTitle.setText(R.string.camera_permission_title)
        binding.statusMetrics.setText(R.string.camera_permission_message)
    }

    private fun startPipeline() {
        binding.makeupRenderer.initializationErrorMessage?.let {
            showFatalRendererState(it)
            return
        }
        if (!trackerReady && !trackerInitializationStarted) {
            trackerInitializationStarted = true
            binding.statusTitle.setText(R.string.status_preparing)
            binding.statusMetrics.setText(R.string.metrics_initial)
            cameraExecutor.execute {
                faceTracker = FaceLandmarkerTracker(
                    context = applicationContext,
                    callbackExecutor = cameraExecutor,
                    listener = this,
                    telemetrySink = trackingTelemetryRecorder,
                    cameraCaptureMetadataStore = cameraCaptureMetadataStore,
                    landmarkPredictionEnabled = landmarkPredictionEnabled,
                ).also { it.initialize() }
            }
        }

        if (!cameraStarted && !cameraBindingStarted) {
            cameraBindingStarted = true
            binding.makeupRenderer.doOnLayout { bindCameraUseCases() }
        }
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bindCameraUseCases() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    showFatalCameraState(R.string.status_camera_unavailable)
                    return@addListener
                }

                val targetRotation = binding.makeupRenderer.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            ContextCompat.getMainExecutor(this),
                            binding.makeupRenderer.surfaceProvider,
                        )
                    }
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(targetRotation)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                if (trackingTelemetryRecorder != null) {
                    Camera2Interop.Extender(analysisBuilder)
                        .setSessionCaptureCallback(cameraCaptureMetadataStore)
                }
                val analysis = analysisBuilder.build()
                    .also { useCase ->
                        useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                            faceTracker?.detect(imageProxy) ?: imageProxy.close()
                        }
                    }

                provider.unbindAll()
                val frontCameraInfo = provider.getCameraInfo(
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                )
                binding.makeupRenderer.setCameraProjectionCalibration(
                    readCameraProjectionCalibration(frontCameraInfo),
                )
                val baseSessionBuilder = SessionConfig.Builder(preview, analysis)
                baseSessionBuilder.setViewPort(createCameraViewPort(targetRotation))
                val baseSession = baseSessionBuilder.build()
                val preferredFrameRate = selectPreferredFrameRate(
                    frontCameraInfo.getSupportedFrameRateRanges(baseSession),
                )
                val session = if (preferredFrameRate != null) {
                    cameraFpsLabel = preferredFrameRate.toDisplayLabel()
                    SessionConfig.Builder(preview, analysis).apply {
                        setViewPort(createCameraViewPort(targetRotation))
                        setFrameRateRange(preferredFrameRate)
                    }.build()
                } else {
                    cameraFpsLabel = getString(R.string.camera_fps_auto)
                    baseSession
                }
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    session,
                )
                val compensationRange = camera.cameraInfo.exposureState.exposureCompensationRange
                val appliedCompensation = cameraExposureCompensationIndex.coerceIn(
                    compensationRange.lower,
                    compensationRange.upper,
                )
                camera.cameraControl.setExposureCompensationIndex(appliedCompensation)
                Log.i(
                    PERFORMANCE_LOG_TAG,
                    "cameraExposureCompensation=$appliedCompensation range=$compensationRange",
                )
                cameraStarted = true
                cameraBindingStarted = false
            } catch (error: Exception) {
                cameraBindingStarted = false
                showFatalCameraState(R.string.status_camera_error, error.message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createCameraViewPort(targetRotation: Int): ViewPort {
        val width = binding.makeupRenderer.width
        val height = binding.makeupRenderer.height
        require(width > 0 && height > 0) {
            "Camera viewport requested before renderer layout: ${width}x$height"
        }
        Log.i(PERFORMANCE_LOG_TAG, "cameraViewport=${width}x$height rotation=$targetRotation")
        return ViewPort.Builder(Rational(width, height), targetRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun readCameraProjectionCalibration(
        cameraInfo: androidx.camera.core.CameraInfo,
    ): CameraProjectionCalibration? {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val focalLength = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        )?.firstOrNull() ?: return null
        val physicalSize = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
        ) ?: return null
        return CameraProjectionCalibration.fromPhysicalSensor(
            focalLengthMillimeters = focalLength,
            sensorWidthMillimeters = physicalSize.width,
            sensorHeightMillimeters = physicalSize.height,
        )
    }

    override fun onTrackerReady(delegate: FaceLandmarkerTracker.InferenceDelegate) {
        trackerReady = true
        runOnUiThread {
            if (!isDestroyed) {
                binding.statusTitle.setText(R.string.status_waiting_for_face)
                binding.statusMetrics.text = getString(delegate.labelRes)
            }
        }
    }

    override fun onTrackingResult(result: FaceLandmarkerTracker.TrackingResult) {
        val fpsReading = fpsMeter.onFrame()
        if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            fpsReading.updated
        ) {
            Log.d(
                PERFORMANCE_LOG_TAG,
                "mlFps=${fpsReading.fps} latencyMs=${result.latencyMs} " +
                    "delegate=${result.delegate.name} camera=$cameraFpsLabel " +
                    "rotation=${result.rotationDegrees} " +
                    "prediction=${result.renderLandmarks?.predictedOnly == true}",
            )
        }
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread

            val renderLandmarks = result.renderLandmarks
            if (renderLandmarks == null) {
                binding.makeupRenderer.clear()
                binding.faceMeshOverlay.clear()
                binding.statusTitle.setText(R.string.status_waiting_for_face)
                return@runOnUiThread
            }

            binding.makeupRenderer.setResult(
                landmarks = renderLandmarks,
                sourceWidth = result.inputWidth,
                sourceHeight = result.inputHeight,
                rotationDegrees = result.rotationDegrees,
                mirrorHorizontal = result.mirrorHorizontal,
                sensorTimestampNs = result.sensorTimestampNs,
            )
            binding.faceMeshOverlay.setResult(
                landmarks = renderLandmarks,
                sourceWidth = result.inputWidth,
                sourceHeight = result.inputHeight,
                rotationDegrees = result.rotationDegrees,
                mirrorHorizontal = result.mirrorHorizontal,
            )
            val landmarkCount = renderLandmarks.size
            binding.statusTitle.setText(R.string.status_tracking)
            binding.statusMetrics.text = getString(
                R.string.metrics_format,
                landmarkCount,
                fpsReading.fps,
                result.latencyMs,
                getString(result.delegate.labelRes),
                cameraFpsLabel,
                binding.makeupRenderer.renderBackendLabel,
            )
        }
    }

    override fun onTrackerError(message: String) {
        runOnUiThread {
            if (!isDestroyed) {
                binding.makeupRenderer.clear()
                binding.faceMeshOverlay.clear()
                binding.statusTitle.setText(R.string.status_tracker_error)
                binding.statusMetrics.text = message
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.makeupRenderer.onResumeRenderer()

        if (hasCameraPermission()) {
            binding.permissionPanel.visibility = View.GONE
            startPipeline()
        } else {
            cameraProvider?.unbindAll()
            cameraStarted = false
            cameraBindingStarted = false
            showPermissionPanel()
        }
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.makeupRenderer.onPauseRenderer()
        super.onPause()
    }

    private fun showFatalCameraState(titleRes: Int, details: String? = null) {
        binding.statusTitle.setText(titleRes)
        binding.statusMetrics.text = details.orEmpty()
        binding.permissionPanel.visibility = View.GONE
    }

    private fun showFatalRendererState(details: String) {
        if (!::binding.isInitialized || isDestroyed) return
        cameraProvider?.unbindAll()
        cameraStarted = false
        cameraBindingStarted = false
        binding.makeupRenderer.clear()
        binding.faceMeshOverlay.clear()
        binding.statusTitle.setText(R.string.status_renderer_error)
        binding.statusMetrics.text = details
        binding.permissionPanel.visibility = View.GONE
    }

    private fun selectPreferredFrameRate(ranges: Set<Range<Int>>): Range<Int>? =
        ranges
            .asSequence()
            .filter { it.upper in MIN_CAMERA_FPS..MAX_CAMERA_FPS }
            .sortedWith(
                compareByDescending<Range<Int>> { it.upper }
                    .thenBy { kotlin.math.abs(it.lower - MIN_CAMERA_FPS) },
            )
            .firstOrNull()

    private fun Range<Int>.toDisplayLabel(): String =
        if (lower == upper) "$upper" else "$lower–$upper"

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(stopTrackingTelemetry)
            binding.makeupRenderer.setTrackingTelemetrySink(null)
        }
        trackingTelemetryRecorder?.close()
        trackingTelemetryRecorder = null
        cameraCaptureMetadataStore.clear()
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.execute { faceTracker?.close() }
            cameraExecutor.shutdown()
        }
        if (::binding.isInitialized) binding.makeupRenderer.destroyRenderer()
        super.onDestroy()
    }

    private fun stopTrackingTelemetryRecording() {
        binding.makeupRenderer.setTrackingTelemetrySink(null)
        trackingTelemetryRecorder?.requestStop()
    }

    private class FpsMeter {
        private var windowStartedAtMs = 0L
        private var frameCount = 0
        private var latestFps = 0f

        @Synchronized
        fun onFrame(nowMs: Long = android.os.SystemClock.uptimeMillis()): Reading {
            if (windowStartedAtMs == 0L) windowStartedAtMs = nowMs
            frameCount++
            val elapsed = nowMs - windowStartedAtMs
            var updated = false
            if (elapsed >= FPS_WINDOW_MS) {
                latestFps = frameCount * 1_000f / elapsed
                frameCount = 0
                windowStartedAtMs = nowMs
                updated = true
            }
            return Reading(latestFps, updated)
        }

        data class Reading(val fps: Float, val updated: Boolean)

        companion object {
            private const val FPS_WINDOW_MS = 1_000L
        }
    }

    companion object {
        const val EXTRA_DISABLE_GYROSCOPE_CORRECTION =
            "com.example.armakeup.extra.DISABLE_GYROSCOPE_CORRECTION"
        const val EXTRA_DISABLE_LANDMARK_PREDICTOR =
            "com.example.armakeup.extra.DISABLE_LANDMARK_PREDICTOR"
        const val EXTRA_ENABLE_DISPLAY_QUEUE_PROTECTION =
            "com.example.armakeup.extra.ENABLE_DISPLAY_QUEUE_PROTECTION"
        const val EXTRA_DISABLE_FILAMENT_PRESENTATION_HINTS =
            "com.example.armakeup.extra.DISABLE_FILAMENT_PRESENTATION_HINTS"
        const val EXTRA_ENABLE_NATIVE_VULKAN_VISIBLE =
            "com.example.armakeup.extra.ENABLE_NATIVE_VULKAN_VISIBLE"
        const val EXTRA_ENABLE_VULKAN_SAME_FRAME_FLOW_VISIBLE =
            "com.example.armakeup.extra.ENABLE_VULKAN_SAME_FRAME_FLOW_VISIBLE"
        const val EXTRA_NATIVE_VULKAN_PRESENT_ON_CAMERA_FRAMES_ONLY =
            "com.example.armakeup.extra.NATIVE_VULKAN_PRESENT_ON_CAMERA_FRAMES_ONLY"
        const val EXTRA_CAMERA_EXPOSURE_COMPENSATION_INDEX =
            "com.example.armakeup.extra.CAMERA_EXPOSURE_COMPENSATION_INDEX"
        const val EXTRA_ENABLE_ARCORE_FACE_ANCHOR_PROOF =
            "com.example.armakeup.extra.ENABLE_ARCORE_FACE_ANCHOR_PROOF"
        private const val MIN_CAMERA_FPS = 30
        private const val MAX_CAMERA_FPS = 60
        private const val PERFORMANCE_LOG_TAG = "ARMakeupPerf"
    }
}
