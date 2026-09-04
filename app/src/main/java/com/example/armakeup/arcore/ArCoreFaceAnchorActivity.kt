package com.example.armakeup.arcore

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.armakeup.R
import com.example.armakeup.databinding.ActivityArcoreFaceAnchorBinding
import com.example.armakeup.makeup.LipstickFinish
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.util.EnumSet

/**
 * Primary real-time ARCore + MediaPipe face-tracking screen.
 *
 * ARCore owns the front camera, the current global face pose, and the camera timeline. MediaPipe
 * asynchronously supplies local lip deformation from latest-only ARCore CPU images; the renderer
 * transports that measured contour to the current ARCore mouth anchor. The previous CameraX and
 * Filament pipeline remains in [com.example.armakeup.MainActivity] as an internal rollback path.
 */
class ArCoreFaceAnchorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArcoreFaceAnchorBinding
    private val surfaceView: GLSurfaceView get() = binding.arcoreSurface
    private lateinit var renderer: ArCoreFaceAnchorRenderer

    private var session: Session? = null
    private var mediaPipeTracker: ArCoreMediaPipeLipTracker? = null
    private var mediaPipeImageRotationDegrees = 0
    private var installRequested = false
    private val choreographer = Choreographer.getInstance()
    private val renderFrameCallback = Choreographer.FrameCallback(::onRenderVsync)
    private var renderSchedulerRunning = false
    private var lastRenderRequestNs = 0L
    private var resumed = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            binding.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
            if (granted) {
                startSessionIfPossible()
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
        binding = ActivityArcoreFaceAnchorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.keepScreenOn = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.preserveEGLContextOnPause = true
        renderer = ArCoreFaceAnchorRenderer(
            displayRotation = { currentDisplayRotation() },
            imageRotationDegrees = { mediaPipeImageRotationDegrees },
            onStatus = { status -> runOnUiThread { showTrackingStatus(status) } },
            onFatalError = { message -> runOnUiThread { showFatal(message) } },
        )
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        binding.finishToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val finish = when (checkedId) {
                R.id.finish_matte -> LipstickFinish.MATTE
                R.id.finish_gloss -> LipstickFinish.GLOSS
                R.id.finish_tracking_test -> LipstickFinish.TRACKING_TEST
                else -> LipstickFinish.SATIN
            }
            renderer.setLipstickFinish(finish)
        }
        binding.timelineToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sameFrame = checkedId == R.id.timeline_same_frame
            surfaceView.queueEvent {
                renderer.setSameFrameSynchronizationEnabled(sameFrame)
            }
        }
        binding.permissionButton.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        applyWindowInsets()
        showTrackingStatus(getString(R.string.status_preparing))
        if (!hasCameraPermission()) showPermissionPanel()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startSessionIfPossible()
    }

    override fun onPause() {
        resumed = false
        surfaceView.queueEvent { renderer.invalidateSameFrameState() }
        surfaceView.onPause()
        session?.pause()
        stopRenderScheduler()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.bindMediaPipeTracker(null)
        mediaPipeTracker?.close()
        mediaPipeTracker = null
        renderer.bindSession(null)
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun startSessionIfPossible() {
        if (!resumed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showPermissionPanel()
            return
        }

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        showTrackingStatus("AR Makeup: waiting for Google Play Services for AR")
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                val newSession = Session(
                    this,
                    EnumSet.of(Session.Feature.FRONT_CAMERA),
                )
                val config = Config(newSession).apply {
                    augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                newSession.configure(config)
                mediaPipeImageRotationDegrees = calculateImageRotationDegrees(newSession)
                session = newSession
                renderer.bindSession(newSession)
                ArCoreMediaPipeLipTracker(
                    context = applicationContext,
                    onError = { message -> Log.w(TAG, message) },
                ).also { tracker ->
                    tracker.initialize()
                    mediaPipeTracker = tracker
                    renderer.bindMediaPipeTracker(tracker)
                }
            }

            session?.resume()
            surfaceView.onResume()
            startRenderScheduler()
            showTrackingStatus(getString(R.string.status_waiting_for_face))
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start hybrid face tracking", error)
            showFatal("Face tracking initialization failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel() {
        binding.permissionPanel.visibility = View.VISIBLE
        binding.statusTitle.setText(R.string.camera_permission_title)
        binding.statusMetrics.setText(R.string.camera_permission_message)
    }

    private fun showTrackingStatus(status: String) {
        if (hasCameraPermission()) binding.permissionPanel.visibility = View.GONE
        binding.statusCard.setCardBackgroundColor(
            ContextCompat.getColor(this, R.color.glass_900),
        )
        val lines = status.lines()
        binding.statusTitle.text = when (lines.firstOrNull()) {
            "FACE TRACKING" -> getString(R.string.status_tracking)
            "SEARCHING FOR FACE" -> getString(R.string.status_waiting_for_face)
            else -> lines.firstOrNull().orEmpty()
        }
        binding.statusMetrics.text = lines.drop(1).joinToString("\n").ifEmpty {
            getString(R.string.metrics_initial)
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

    private fun showFatal(message: String) {
        Log.e(TAG, message)
        binding.statusTitle.setText(R.string.status_tracker_error)
        binding.statusMetrics.text = message
        binding.statusCard.setCardBackgroundColor(0xE68B1E32.toInt())
        binding.permissionPanel.visibility = View.GONE
    }

    private fun startRenderScheduler() {
        if (renderSchedulerRunning) return
        renderSchedulerRunning = true
        lastRenderRequestNs = 0L
        choreographer.postFrameCallback(renderFrameCallback)
    }

    private fun stopRenderScheduler() {
        if (!renderSchedulerRunning) return
        renderSchedulerRunning = false
        lastRenderRequestNs = 0L
        choreographer.removeFrameCallback(renderFrameCallback)
    }

    private fun onRenderVsync(frameTimeNs: Long) {
        if (!renderSchedulerRunning) return
        if (
            lastRenderRequestNs == 0L ||
            frameTimeNs - lastRenderRequestNs >= TARGET_RENDER_INTERVAL_NS
        ) {
            lastRenderRequestNs = frameTimeNs
            surfaceView.requestRender()
        }
        choreographer.postFrameCallback(renderFrameCallback)
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        windowManager.defaultDisplay.rotation
    }

    private fun calculateImageRotationDegrees(activeSession: Session): Int {
        val cameraManager = getSystemService(CameraManager::class.java)
        val characteristics = cameraManager.getCameraCharacteristics(
            activeSession.cameraConfig.cameraId,
        )
        val sensorOrientation = characteristics
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (currentDisplayRotation()) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation + displayDegrees) % 360
    }

    private companion object {
        const val TAG = "ARMakeupArCore"
        const val TARGET_RENDER_INTERVAL_NS = 1_000_000_000L / 60L
    }
}
