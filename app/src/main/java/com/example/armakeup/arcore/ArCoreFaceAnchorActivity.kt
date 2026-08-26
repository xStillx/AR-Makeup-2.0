package com.example.armakeup.arcore

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Build
import android.view.Choreographer
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var renderer: ArCoreFaceAnchorRenderer

    private var session: Session? = null
    private var mediaPipeTracker: ArCoreMediaPipeLipTracker? = null
    private var mediaPipeImageRotationDegrees = 0
    private var installRequested = false
    private val choreographer = Choreographer.getInstance()
    private val renderFrameCallback = Choreographer.FrameCallback(::onRenderVsync)
    private var renderSchedulerRunning = false
    private var lastRenderRequestNs = 0L
    private var permissionRequested = false
    private var resumed = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequested = false
            if (granted) {
                startSessionIfPossible()
            } else {
                showFatal("Camera permission is required for face tracking")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(0xB3181518.toInt())
            setPadding(28, 24, 28, 24)
            text = "AR Makeup: initializing face tracking"
        }
        renderer = ArCoreFaceAnchorRenderer(
            displayRotation = { currentDisplayRotation() },
            imageRotationDegrees = { mediaPipeImageRotationDegrees },
            onStatus = { status -> runOnUiThread { statusView.text = status } },
            onFatalError = { message -> runOnUiThread { showFatal(message) } },
        )
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        val root = FrameLayout(this).apply {
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).apply {
                    leftMargin = 24
                    topMargin = 72
                    rightMargin = 24
                },
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startSessionIfPossible()
    }

    override fun onPause() {
        resumed = false
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
            if (!permissionRequested) {
                permissionRequested = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        statusView.text = "AR Makeup: waiting for Google Play Services for AR"
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
            statusView.text = "AR Makeup: point the front camera at your face"
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start hybrid face tracking", error)
            showFatal("Face tracking initialization failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun showFatal(message: String) {
        Log.e(TAG, message)

        statusView.text = message
        statusView.setBackgroundColor(0xD08B1E32.toInt())
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
