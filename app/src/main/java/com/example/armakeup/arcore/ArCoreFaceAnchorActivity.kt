package com.example.armakeup.arcore

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
import java.util.Locale
import kotlin.math.roundToInt

/** Primary real-time ARCore + MediaPipe face-tracking and material-tuning screen. */
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
    private var selectedFinish = LipstickFinish.GLOSS
    private var tuning = LipstickTuning.defaultsFor(selectedFinish)
    private var tuningExpanded = true
    private var updatingTuningControls = false
    private val tuningControls = mutableListOf<TuningControl>()

    private data class TuningControl(
        val parameter: LipstickTuningParameter,
        val valueView: TextView,
        val seekBar: SeekBar,
    )

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            binding.permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
            if (granted) startSessionIfPossible() else showPermissionPanel()
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
            onStatus = {},
            onFatalError = { message -> runOnUiThread { showFatal(message) } },
        )
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        tuning = restoreTuning(selectedFinish)
        renderer.setLipstickTuning(tuning)
        renderer.setLipstickFinish(selectedFinish)
        createTuningControls()
        bindPanelActions()

        binding.finishToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val finish = when (checkedId) {
                R.id.finish_matte -> LipstickFinish.MATTE
                R.id.finish_gloss -> LipstickFinish.GLOSS
                R.id.finish_tracking_test -> LipstickFinish.TRACKING_TEST
                else -> LipstickFinish.SATIN
            }
            if (finish == selectedFinish) return@addOnButtonCheckedListener
            saveTuning()
            selectedFinish = finish
            tuning = restoreTuning(selectedFinish)
            renderer.setLipstickTuning(tuning)
            renderer.setLipstickFinish(finish)
            updateTuningControls()
        }
        binding.timelineToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sameFrame = checkedId == R.id.timeline_same_frame
            surfaceView.queueEvent { renderer.setSameFrameSynchronizationEnabled(sameFrame) }
        }
        binding.permissionButton.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        applyWindowInsets()
        if (!hasCameraPermission()) showPermissionPanel()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startSessionIfPossible()
    }

    override fun onPause() {
        saveTuning()
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

    private fun bindPanelActions() {
        binding.toggleTuning.setOnClickListener {
            tuningExpanded = !tuningExpanded
            binding.tuningScroll.visibility = if (tuningExpanded) View.VISIBLE else View.GONE
            binding.toggleTuning.setText(
                if (tuningExpanded) R.string.tuning_collapse else R.string.tuning_expand,
            )
        }
        binding.resetTuning.setOnClickListener {
            tuning = LipstickTuning.defaultsFor(selectedFinish)
            renderer.setLipstickTuning(tuning)
            updateTuningControls()
            saveTuning()
        }
        binding.copyTuning.setOnClickListener {
            val text = buildString {
                appendLine("finish=${selectedFinish.name}")
                LipstickTuningParameter.values().forEach { parameter ->
                    append(parameter.name.lowercase(Locale.US))
                    append('=')
                    appendLine(formatValue(parameter, parameter.read(tuning)))
                }
            }.trimEnd()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AR Makeup lipstick tuning", text))
            Toast.makeText(this, R.string.tuning_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTuningControls() {
        var currentGroup: String? = null
        LipstickTuningParameter.values().forEach { parameter ->
            if (parameter.group != currentGroup) {
                currentGroup = parameter.group
                binding.tuningContainer.addView(createSectionTitle(parameter.group))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = parameter.label
                setTextColor(ContextCompat.getColor(context, R.color.text_primary_on_dark))
                textSize = 13f
            }
            val value = TextView(this).apply {
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(context, R.color.rose_300))
                textSize = 12f
            }
            header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(value, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
            val seekBar = SeekBar(this).apply {
                max = SLIDER_STEPS
                progress = progressFor(parameter, parameter.read(tuning))
                setPadding(0, 0, 0, 0)
            }
            value.text = formatValue(parameter, parameter.read(tuning))
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (updatingTuningControls) return
                    val nextValue = valueFor(parameter, progress)
                    value.text = formatValue(parameter, nextValue)
                    tuning = parameter.update(tuning, nextValue)
                    renderer.setLipstickTuning(tuning)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    saveTuning()
                }
            })
            row.addView(header)
            row.addView(seekBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(30),
            ))
            binding.tuningContainer.addView(row)
            tuningControls += TuningControl(parameter, value, seekBar)
        }
    }

    private fun createSectionTitle(title: String) = TextView(this).apply {
        text = title
        setTextColor(ContextCompat.getColor(context, R.color.rose_300))
        textSize = 11f
        letterSpacing = 0.08f
        setPadding(0, dp(14), 0, dp(3))
    }

    private fun updateTuningControls() {
        updatingTuningControls = true
        tuningControls.forEach { control ->
            val value = control.parameter.read(tuning)
            control.valueView.text = formatValue(control.parameter, value)
            control.seekBar.progress = progressFor(control.parameter, value)
        }
        updatingTuningControls = false
    }

    private fun progressFor(parameter: LipstickTuningParameter, value: Float): Int =
        (((value - parameter.minimum) / (parameter.maximum - parameter.minimum)) * SLIDER_STEPS)
            .roundToInt()
            .coerceIn(0, SLIDER_STEPS)

    private fun valueFor(parameter: LipstickTuningParameter, progress: Int): Float =
        parameter.minimum + (parameter.maximum - parameter.minimum) * progress / SLIDER_STEPS

    private fun formatValue(parameter: LipstickTuningParameter, value: Float): String =
        String.format(Locale.US, "%.${parameter.decimals}f", value)

    private fun restoreTuning(finish: LipstickFinish): LipstickTuning {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        val defaults = LipstickTuning.defaultsFor(finish)
        return LipstickTuningParameter.values().fold(defaults) { state, parameter ->
            val defaultValue = parameter.read(defaults)
            val storedValue = preferences.getFloat(preferenceKey(finish, parameter), defaultValue)
                .coerceIn(parameter.minimum, parameter.maximum)
            parameter.update(state, storedValue)
        }
    }

    private fun saveTuning() {
        getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE).edit().apply {
            LipstickTuningParameter.values().forEach { parameter ->
                putFloat(preferenceKey(selectedFinish, parameter), parameter.read(tuning))
            }
            apply()
        }
    }

    private fun preferenceKey(
        finish: LipstickFinish,
        parameter: LipstickTuningParameter,
    ): String = "${tuningProfileKey(finish)}_${parameter.name}"

    private fun tuningProfileKey(finish: LipstickFinish): String = when (finish) {
        LipstickFinish.GLOSS -> "GLOSS_LOREAL_BROWN_ESPRESSO_515_FINAL_643229"
        else -> finish.name
    }

    private fun startSessionIfPossible() {
        if (!resumed) return
        if (!hasCameraPermission()) {
            showPermissionPanel()
            return
        }

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                val newSession = Session(this, EnumSet.of(Session.Feature.FRONT_CAMERA))
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
            binding.permissionPanel.visibility = View.GONE
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start hybrid face tracking", error)
            showFatal("Face tracking initialization failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel() {
        binding.permissionPanel.setBackgroundResource(R.color.glass_900)
        binding.permissionPanel.visibility = View.VISIBLE
        binding.permissionTitle.setText(R.string.camera_permission_title)
        binding.permissionMessage.setText(R.string.camera_permission_message)
        binding.permissionButton.visibility = View.VISIBLE
    }

    private fun applyWindowInsets() {
        val phaseBaseMargin = binding.phaseCard.marginBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.phaseCard.updateVerticalMargin(bottom = phaseBaseMargin + bars.bottom)
            insets
        }
    }

    private fun View.updateVerticalMargin(bottom: Int) {
        val params = layoutParams as ViewGroup.MarginLayoutParams
        params.bottomMargin = bottom
        layoutParams = params
    }

    private val View.marginBottom: Int
        get() = (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

    private fun showFatal(message: String) {
        Log.e(TAG, message)
        binding.permissionTitle.setText(R.string.status_tracker_error)
        binding.permissionMessage.text = message
        binding.permissionPanel.setBackgroundColor(0xE68B1E32.toInt())
        binding.permissionButton.visibility = View.GONE
        binding.permissionPanel.visibility = View.VISIBLE
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
        if (lastRenderRequestNs == 0L || frameTimeNs - lastRenderRequestNs >= TARGET_RENDER_INTERVAL_NS) {
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
        val characteristics = cameraManager.getCameraCharacteristics(activeSession.cameraConfig.cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (currentDisplayRotation()) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation + displayDegrees) % 360
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "ARMakeupArCore"
        const val TARGET_RENDER_INTERVAL_NS = 1_000_000_000L / 60L
        const val SLIDER_STEPS = 1_000
        const val TUNING_PREFERENCES = "lipstick_tuning_v2"
    }
}
