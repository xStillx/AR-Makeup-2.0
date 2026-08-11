package com.example.armakeup

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.armakeup.databinding.ActivityMainBinding
import com.example.armakeup.tracking.FaceLandmarkerTracker
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
    private val fpsMeter = FpsMeter()

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
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.keepScreenOn = true

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
        if (!trackerReady && !trackerInitializationStarted) {
            trackerInitializationStarted = true
            binding.statusTitle.setText(R.string.status_preparing)
            binding.statusMetrics.setText(R.string.metrics_initial)
            cameraExecutor.execute {
                faceTracker = FaceLandmarkerTracker(
                    context = applicationContext,
                    callbackExecutor = cameraExecutor,
                    listener = this,
                ).also { it.initialize() }
            }
        }

        if (!cameraStarted && !cameraBindingStarted) {
            cameraBindingStarted = true
            binding.cameraPreview.post { bindCameraUseCases() }
        }
    }

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

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.cameraPreview.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                            faceTracker?.detect(imageProxy) ?: imageProxy.close()
                        }
                    }

                provider.unbindAll()
                val baseSessionBuilder = SessionConfig.Builder(preview, analysis)
                binding.cameraPreview.viewPort?.let(baseSessionBuilder::setViewPort)
                val baseSession = baseSessionBuilder.build()
                val preferredFrameRate = selectPreferredFrameRate(
                    provider.getCameraInfo(CameraSelector.DEFAULT_FRONT_CAMERA)
                        .getSupportedFrameRateRanges(baseSession),
                )
                val session = if (preferredFrameRate != null) {
                    cameraFpsLabel = preferredFrameRate.toDisplayLabel()
                    SessionConfig.Builder(preview, analysis).apply {
                        binding.cameraPreview.viewPort?.let(::setViewPort)
                        setFrameRateRange(preferredFrameRate)
                    }.build()
                } else {
                    cameraFpsLabel = getString(R.string.camera_fps_auto)
                    baseSession
                }
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    session,
                )
                cameraStarted = true
                cameraBindingStarted = false
            } catch (error: Exception) {
                cameraBindingStarted = false
                showFatalCameraState(R.string.status_camera_error, error.message)
            }
        }, ContextCompat.getMainExecutor(this))
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
                binding.lipstickOverlay.clear()
                binding.faceMeshOverlay.clear()
                binding.statusTitle.setText(R.string.status_waiting_for_face)
                return@runOnUiThread
            }

            binding.lipstickOverlay.setResult(
                landmarks = renderLandmarks,
                sourceWidth = result.inputWidth,
                sourceHeight = result.inputHeight,
                rotationDegrees = result.rotationDegrees,
                mirrorHorizontal = result.mirrorHorizontal,
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
            )
        }
    }

    override fun onTrackerError(message: String) {
        runOnUiThread {
            if (!isDestroyed) {
                binding.lipstickOverlay.clear()
                binding.faceMeshOverlay.clear()
                binding.statusTitle.setText(R.string.status_tracker_error)
                binding.statusMetrics.text = message
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return

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

    private fun showFatalCameraState(titleRes: Int, details: String? = null) {
        binding.statusTitle.setText(titleRes)
        binding.statusMetrics.text = details.orEmpty()
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
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.execute { faceTracker?.close() }
            cameraExecutor.shutdown()
        }
        super.onDestroy()
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
        private const val MIN_CAMERA_FPS = 30
        private const val MAX_CAMERA_FPS = 60
        private const val PERFORMANCE_LOG_TAG = "ARMakeupPerf"
    }
}
