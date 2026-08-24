package com.example.armakeup.arcore

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.armakeup.MainActivity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.util.EnumSet

/**
 * Isolated ARCore Augmented Faces proof.
 *
 * ARCore is always the sole front-camera owner. The default mode keeps the isolated OpenGL proof;
 * the explicit native-Vulkan extra switches presentation to a direct ARCore HardwareBuffer import
 * while preserving the same ARCore + MediaPipe hybrid face-state contract.
 */
class ArCoreFaceAnchorActivity : AppCompatActivity() {

    private lateinit var surfaceView: View
    private lateinit var statusView: TextView
    private var glSurfaceView: GLSurfaceView? = null
    private var glRenderer: ArCoreFaceAnchorRenderer? = null
    private var vulkanRenderer: ArCoreVulkanFaceRenderer? = null
    private var useVulkan = false
    private var faceDepthEnabled = false

    private var session: Session? = null
    private var mediaPipeTracker: ArCoreMediaPipeLipTracker? = null
    private var mediaPipeImageRotationDegrees = 0
    private var installRequested = false
    private var permissionRequested = false
    private var resumed = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequested = false
            if (granted) {
                startSessionIfPossible()
            } else {
                showFatal("ARCore proof requires camera permission")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val vulkanRequested =
            intent.getBooleanExtra(MainActivity.EXTRA_ENABLE_NATIVE_VULKAN_VISIBLE, false)
        useVulkan = vulkanRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        faceDepthEnabled = useVulkan &&
            intent.getBooleanExtra(MainActivity.EXTRA_ENABLE_VULKAN_FACE_DEPTH, false)
        surfaceView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && useVulkan) {
            SurfaceView(this).also { view ->
                vulkanRenderer = ArCoreVulkanFaceRenderer(
                    surfaceView = view,
                    faceDepthEnabled = faceDepthEnabled,
                    displayRotation = { currentDisplayRotation() },
                    imageRotationDegrees = { mediaPipeImageRotationDegrees },
                    onStatus = { status -> runOnUiThread { statusView.text = status } },
                    onFatalError = { message -> runOnUiThread { showFatal(message) } },
                )
            }
        } else {
            GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)
                preserveEGLContextOnPause = true
            }.also { view -> glSurfaceView = view }
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(0xB3181518.toInt())
            setPadding(28, 24, 28, 24)
            text = "ARCore: initialization"
        }
        glSurfaceView?.let { view ->
            glRenderer = ArCoreFaceAnchorRenderer(
                displayRotation = { currentDisplayRotation() },
                imageRotationDegrees = { mediaPipeImageRotationDegrees },
                onStatus = { status -> runOnUiThread { statusView.text = status } },
                onFatalError = { message -> runOnUiThread { showFatal(message) } },
            ).also { renderer ->
                view.setRenderer(renderer)
                view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

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
        vulkanRenderer?.pause()
        glSurfaceView?.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        glRenderer?.bindMediaPipeTracker(null)
        vulkanRenderer?.bindMediaPipeTracker(null)
        mediaPipeTracker?.close()
        mediaPipeTracker = null
        glRenderer?.bindSession(null)
        vulkanRenderer?.bindSession(null)
        session?.close()
        session = null
        vulkanRenderer?.close()
        vulkanRenderer = null
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
                        statusView.text = "ARCore: waiting for Google Play Services for AR"
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && useVulkan) {
                        textureUpdateMode = Config.TextureUpdateMode.EXPOSE_HARDWARE_BUFFER
                    }
                }
                newSession.configure(config)
                mediaPipeImageRotationDegrees = calculateImageRotationDegrees(newSession)
                session = newSession
                glRenderer?.bindSession(newSession)
                vulkanRenderer?.bindSession(newSession)
                ArCoreMediaPipeLipTracker(
                    context = applicationContext,
                    onError = { message -> Log.w(TAG, message) },
                ).also { tracker ->
                    tracker.initialize()
                    mediaPipeTracker = tracker
                    glRenderer?.bindMediaPipeTracker(tracker)
                    vulkanRenderer?.bindMediaPipeTracker(tracker)
                }
            }

            session?.resume()
            glSurfaceView?.onResume()
            vulkanRenderer?.resume()
            statusView.text = if (useVulkan) {
                if (faceDepthEnabled) {
                    "ARCore + Vulkan 3D depth: point the front camera at your face"
                } else {
                    "ARCore + Vulkan 2D baseline: point the front camera at your face"
                }
            } else {
                "ARCore: point the front camera at your face"
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start ARCore face-anchor proof", error)
            showFatal("ARCore initialization failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun showFatal(message: String) {
        Log.e(TAG, message)
        statusView.text = message
        statusView.setBackgroundColor(0xD08B1E32.toInt())
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
    }
}
