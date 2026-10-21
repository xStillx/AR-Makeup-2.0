package com.example.armakeup.arcore

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
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
import com.example.armakeup.makeup.LipColorRenderingMode
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.makeup.LipstickDensity
import com.example.armakeup.makeup.LipstickTexture
import com.example.armakeup.makeup.LipstickColor
import com.example.armakeup.makeup.LipstickColorCatalog
import com.example.armakeup.makeup.LipColorSwatch
import com.example.armakeup.makeup.LipLinerCatalog
import com.example.armakeup.makeup.LipLinerStyle
import com.example.armakeup.makeup.LipMeshTessellator
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
    private var selectedColorRenderingMode = LipColorRenderingMode.IOS_REFERENCE
    private var selectedFinish = LipstickFinish.MATTE
    private var lipstickEnabled = true
    private var lowerLipExtensionPixels = LipMeshTessellator.DEFAULT_LOWER_OUTER_EXTENSION_PIXELS
    private var tuning = LipstickTuning.defaultsFor(selectedColorRenderingMode, selectedFinish)
    private var tuningExpanded = true
    private var updatingTuningControls = false
    private val tuningControls = mutableListOf<TuningControl>()
    private var sharedPigment: LipstickColor? = null
    private var sharedProductDensity: LipstickDensity? = null
    private var sharedProductTexture: LipstickTexture? = null
    private val sharedPigmentOpacities = mutableMapOf<LipColorRenderingMode, Float>()
    private var selectedShadeKey: String? = null
    private val shadeControls = mutableListOf<ShadeControl>()
    private var editingLiner = false
    private var selectedLinerShadeId = LipLinerCatalog.shades.first().id
    private var linerStyle = LipLinerStyle()
    private var updatingLinerControls = false

    private data class PaletteEntry(
        val key: String, val product: String, val name: String, val color: LipstickColor,
        val select: () -> Unit,
    )

    private data class ShadeControl(
        val key: String,
        val item: View,
        val selectionRing: View,
        val shadeLabel: TextView,
    )

    private data class TuningControl(
        val parameter: LipstickTuningParameter,
        val row: View,
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

        selectedColorRenderingMode = restoreColorRenderingMode()
        binding.colorRenderingModeToggleGroup.check(
            if (selectedColorRenderingMode == LipColorRenderingMode.IOS_REFERENCE) {
                R.id.color_rendering_mode_ios
            } else {
                R.id.color_rendering_mode_unified
            },
        )
        restoreSharedSelection()
        updateFinishSelection()
        tuning = restoreTuning(selectedColorRenderingMode, selectedFinish)
        renderer.setColorRenderingMode(selectedColorRenderingMode)
        renderer.setLipstickTuning(tuning)
        renderer.setLipstickFinish(selectedFinish)
        bindLipstickVisibility()
        bindLowerLipCoverage()
        bindLipContourPoints()
        bindLinerControls()
        createShadePalette()
        createTuningControls()
        bindPanelActions()

        binding.colorRenderingModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val renderingMode = if (checkedId == R.id.color_rendering_mode_unified) {
                LipColorRenderingMode.UNIFIED
            } else {
                LipColorRenderingMode.IOS_REFERENCE
            }
            if (renderingMode == selectedColorRenderingMode) return@addOnButtonCheckedListener
            saveTuning()
            selectedColorRenderingMode = renderingMode
            saveColorRenderingMode()
            selectedFinish = restoreFinishForMode(renderingMode, selectedFinish)
            updateFinishSelection()
            tuning = restoreTuning(selectedColorRenderingMode, selectedFinish)
            renderer.setColorRenderingMode(selectedColorRenderingMode)
            renderer.setLipstickTuning(tuning)
            renderer.setLipstickFinish(selectedFinish)
            updateTuningControls()
        }

        binding.finishToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val finish = when (checkedId) {
                R.id.finish_matte -> LipstickFinish.MATTE
                R.id.finish_gloss -> LipstickFinish.GLOSS
                R.id.finish_oil -> LipstickFinish.OIL
                R.id.finish_tint -> LipstickFinish.TINT
                R.id.finish_tracking_test -> LipstickFinish.TRACKING_TEST
                else -> LipstickFinish.SATIN
            }
            if (!finish.supportedIn(selectedColorRenderingMode)) return@addOnButtonCheckedListener
            if (finish == selectedFinish) return@addOnButtonCheckedListener
            saveTuning()
            selectedFinish = finish
            tuning = restoreTuning(selectedColorRenderingMode, selectedFinish)
            renderer.setLipstickTuning(tuning)
            renderer.setLipstickFinish(finish)
            updateTuningControls()
            saveTuning()
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
            updateToolPanels()
            binding.toggleTuning.setText(
                if (tuningExpanded) R.string.tuning_collapse else R.string.tuning_expand,
            )
        }
        binding.resetTuning.setOnClickListener {
            if (editingLiner) {
                linerStyle = linerStyle.copy(opacity = 0.76f, thickness = 2f, blur = 2f)
                updateLinerControls()
                persistLiner()
                return@setOnClickListener
            }
            if (selectedFinish != LipstickFinish.TRACKING_TEST) {
                sharedProductDensity?.let {
                    sharedPigmentOpacities[selectedColorRenderingMode] = it.initialPigmentOpacity(selectedColorRenderingMode)
                } ?: sharedPigmentOpacities.remove(selectedColorRenderingMode)
            }
            tuning = withSharedSelection(
                LipstickTuning.defaultsFor(selectedColorRenderingMode, selectedFinish),
                selectedColorRenderingMode, selectedFinish,
            )
            renderer.setLipstickTuning(tuning)
            updateTuningControls()
            binding.lowerLipCoverage.progress = LipMeshTessellator.DEFAULT_LOWER_OUTER_EXTENSION_PIXELS.roundToInt()
            saveTuning()
        }
        binding.copyTuning.setOnClickListener {
            val text = buildString {
                if (editingLiner) {
                    appendLine("tool=LIP_LINER")
                    appendLine("liner_enabled=${linerStyle.enabled}")
                    appendLine("liner_shade=$selectedLinerShadeId")
                    appendLine("liner_red=${linerStyle.color.red8Bit}")
                    appendLine("liner_green=${linerStyle.color.green8Bit}")
                    appendLine("liner_blue=${linerStyle.color.blue8Bit}")
                    appendLine("liner_opacity=${String.format(Locale.US, "%.2f", linerStyle.opacity)}")
                    appendLine("liner_thickness=${String.format(Locale.US, "%.2f", linerStyle.thickness)}")
                    appendLine("liner_blur=${String.format(Locale.US, "%.2f", linerStyle.blur)}")
                    return@buildString
                }
                appendLine("rendering_mode=${selectedColorRenderingMode.name}")
                appendLine("lipstick_enabled=$lipstickEnabled")
                appendLine("lower_lip_outer_extension_pixels=${lowerLipExtensionPixels.roundToInt()}")
                appendLine("finish=${selectedFinish.name}")
                LipstickTuningParameter.values().forEach { parameter ->
                    append(parameter.name.lowercase(Locale.US))
                    append('=')
                    appendLine(formatExportValue(parameter, parameter.read(tuning)))
                }
            }.trimEnd()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AR Makeup lipstick tuning", text))
            Toast.makeText(this, R.string.tuning_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindLipstickVisibility() {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        lipstickEnabled = preferences.getBoolean(LIPSTICK_ENABLED_KEY, true)
        binding.lipstickEnabled.isChecked = lipstickEnabled
        renderer.setLipstickEnabled(lipstickEnabled)
        binding.lipstickEnabled.setOnCheckedChangeListener { _, enabled ->
            lipstickEnabled = enabled
            renderer.setLipstickEnabled(enabled)
            preferences.edit().putBoolean(LIPSTICK_ENABLED_KEY, enabled).apply()
        }
    }

    private fun bindLowerLipCoverage() {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        val defaultPixels = LipMeshTessellator.DEFAULT_LOWER_OUTER_EXTENSION_PIXELS
        // Adopt the user's approved 4px once; later manual choices remain persistent.
        val approvedDefaultAdopted = preferences.getBoolean(LOWER_LIP_EXTENSION_4PX_ADOPTED_KEY, false)
        val stored = if (approvedDefaultAdopted) {
            preferences.getFloat(LOWER_LIP_EXTENSION_PIXELS_KEY, defaultPixels)
        } else defaultPixels
        val progress = (if (stored.isFinite()) stored else defaultPixels)
            .coerceIn(0f, LipMeshTessellator.MAX_LOWER_OUTER_EXTENSION_PIXELS).roundToInt()
        lowerLipExtensionPixels = progress.toFloat()
        if (!approvedDefaultAdopted || !preferences.contains(LOWER_LIP_EXTENSION_PIXELS_KEY) ||
            stored != lowerLipExtensionPixels) {
            preferences.edit().putFloat(LOWER_LIP_EXTENSION_PIXELS_KEY, lowerLipExtensionPixels)
                .putBoolean(LOWER_LIP_EXTENSION_4PX_ADOPTED_KEY, true).apply()
        }
        binding.lowerLipCoverage.max = LipMeshTessellator.MAX_LOWER_OUTER_EXTENSION_PIXELS.roundToInt()
        binding.lowerLipCoverage.keyProgressIncrement = 1
        binding.lowerLipCoverage.progress = progress
        binding.lowerLipCoverageLabel.text = getString(R.string.lower_lip_coverage_value, progress)
        binding.lowerLipCoverage.contentDescription = binding.lowerLipCoverageLabel.text
        renderer.setLowerLipExtensionPixels(lowerLipExtensionPixels)
        binding.lowerLipCoverage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                lowerLipExtensionPixels = progress.toFloat()
                binding.lowerLipCoverageLabel.text = getString(R.string.lower_lip_coverage_value, progress)
                binding.lowerLipCoverage.contentDescription = binding.lowerLipCoverageLabel.text
                renderer.setLowerLipExtensionPixels(lowerLipExtensionPixels)
                preferences.edit().putFloat(LOWER_LIP_EXTENSION_PIXELS_KEY, lowerLipExtensionPixels).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun bindLipContourPoints() {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        val enabled = preferences.getBoolean(LIP_CONTOUR_POINTS_KEY, false)
        binding.lipContourPoints.isChecked = enabled
        renderer.setLipContourPointsEnabled(enabled)
        binding.lipContourPoints.setOnCheckedChangeListener { _, checked ->
            renderer.setLipContourPointsEnabled(checked)
            preferences.edit().putBoolean(LIP_CONTOUR_POINTS_KEY, checked).apply()
        }
    }

    private fun bindLinerControls() {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        // Adopt the user's approved 200%/200% once; later manual changes remain persistent.
        if (!preferences.getBoolean("liner_shape_200_adopted_v1", false)) {
            preferences.edit().putFloat("liner_thickness_v1", 2f)
                .putFloat("liner_blur_v1", 2f)
                .putBoolean("liner_shape_200_adopted_v1", true).apply()
        }
        val shade = LipLinerCatalog.shades.firstOrNull {
            it.id == preferences.getString("liner_shade_v1", null)
        } ?: LipLinerCatalog.shades.first()
        selectedLinerShadeId = shade.id
        linerStyle = LipLinerStyle(
            enabled = preferences.getBoolean("liner_enabled_v1", false),
            color = shade.color,
            opacity = preferences.getFloat("liner_opacity_v1", 0.76f)
                .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.76f,
            thickness = preferences.getFloat("liner_thickness_v1", 2f)
                .takeIf { it.isFinite() }?.coerceIn(0.25f, 2f) ?: 2f,
            blur = preferences.getFloat("liner_blur_v1", 2f)
                .takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 2f,
        )
        editingLiner = preferences.getBoolean("editing_liner_v1", false)
        binding.makeupToolGroup.check(if (editingLiner) R.id.tool_liner else R.id.tool_lipstick)
        updateLinerControls()
        updateToolPanels()
        binding.makeupToolGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            editingLiner = id == R.id.tool_liner
            createShadePalette()
            updateToolPanels()
            persistLiner()
        }
        binding.linerEnabled.setOnCheckedChangeListener { _, enabled ->
            if (updatingLinerControls) return@setOnCheckedChangeListener
            linerStyle = linerStyle.copy(enabled = enabled)
            renderer.setLipLinerStyle(linerStyle)
            persistLiner()
        }
        binding.linerOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingLinerControls || !fromUser) return
                linerStyle = linerStyle.copy(opacity = progress / 100f)
                renderer.setLipLinerStyle(linerStyle)
                binding.linerOpacityLabel.text = getString(R.string.liner_intensity_value, progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = persistLiner()
        })
        bindLinerShapeSlider(binding.linerThickness) { progress ->
            linerStyle.copy(thickness = (progress + 25) / 100f)
        }
        bindLinerShapeSlider(binding.linerBlur) { progress ->
            linerStyle.copy(blur = progress / 100f)
        }
    }

    private fun bindLinerShapeSlider(bar: SeekBar, update: (Int) -> LipLinerStyle) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingLinerControls || !fromUser) return
                linerStyle = update(progress)
                updateLinerControls()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = persistLiner()
        })
    }

    private fun updateToolPanels() {
        binding.tuningScroll.visibility = if (tuningExpanded && !editingLiner) View.VISIBLE else View.GONE
        binding.linerTuningPanel.visibility = if (tuningExpanded && editingLiner) View.VISIBLE else View.GONE
    }

    private fun updateLinerControls() {
        updatingLinerControls = true
        binding.linerEnabled.isChecked = linerStyle.enabled
        val progress = (linerStyle.opacity * 100).roundToInt()
        binding.linerOpacity.progress = progress
        binding.linerOpacityLabel.text = getString(R.string.liner_intensity_value, progress)
        val thicknessPercent = (linerStyle.thickness * 100).roundToInt()
        binding.linerThickness.progress = (thicknessPercent - 25).coerceIn(0, 175)
        binding.linerThicknessLabel.text = getString(R.string.liner_thickness_value, thicknessPercent)
        val blurPercent = (linerStyle.blur * 100).roundToInt()
        binding.linerBlur.progress = blurPercent.coerceIn(0, 200)
        binding.linerBlurLabel.text = getString(R.string.liner_blur_value, blurPercent)
        updatingLinerControls = false
        renderer.setLipLinerStyle(linerStyle)
    }

    private fun persistLiner() {
        getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("liner_shade_v1", selectedLinerShadeId)
            .putBoolean("liner_enabled_v1", linerStyle.enabled)
            .putFloat("liner_opacity_v1", linerStyle.opacity)
            .putFloat("liner_thickness_v1", linerStyle.thickness)
            .putFloat("liner_blur_v1", linerStyle.blur)
            .putBoolean("editing_liner_v1", editingLiner)
            .apply()
    }

    private fun createShadePalette() {
        binding.lipShadeContainer.removeAllViews()
        shadeControls.clear()
        val palette = if (editingLiner) LipLinerCatalog.shades.map { shade ->
            PaletteEntry(shade.id, shade.product, shade.name, shade.color) {
                selectedLinerShadeId = shade.id
                linerStyle = linerStyle.copy(enabled = true, color = shade.color)
                updateLinerControls()
                updateShadeSelection()
                persistLiner()
            }
        } else LipstickColorCatalog.swatches.map { swatch ->
            PaletteEntry(swatch.key, swatch.product.name, swatch.shade.name, swatch.shade.color) { selectShade(swatch) }
        }
        palette.forEach { entry ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(8), dp(6), dp(4))
                isFocusable = true
                contentDescription = getString(
                    R.string.lip_shade_accessibility, entry.product, entry.name,
                )
                val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = attributes.getDrawable(0)
                attributes.recycle()
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    @Suppress("DEPRECATION") // Boolean checked state supports the app's pre-API37 devices.
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                }
                setOnClickListener { entry.select() }
            }
            val productLabel = TextView(this).apply {
                text = entry.product
                textSize = 11f
                minLines = 2
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary_on_dark))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(productLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })

            val circle = FrameLayout(this)
            val color = entry.color
            circle.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(color.red8Bit, color.green8Bit, color.blue8Bit))
                    setStroke(dp(1), ContextCompat.getColor(context, R.color.glass_border))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
            val ring = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), ContextCompat.getColor(context, R.color.rose_300))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            circle.addView(ring, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
            item.addView(circle, LinearLayout.LayoutParams(dp(58), dp(58)))

            val shadeLabel = TextView(this).apply {
                text = entry.name
                textSize = 12f
                minLines = 2
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(shadeLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) })
            binding.lipShadeContainer.addView(item, LinearLayout.LayoutParams(
                dp(128), ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            shadeControls += ShadeControl(entry.key, item, ring, shadeLabel)
        }
        updateShadeSelection()
        binding.lipShadeScroll.post {
            shadeControls.firstOrNull { it.key == (if (editingLiner) selectedLinerShadeId else selectedShadeKey) }?.item?.let { selected ->
                binding.lipShadeScroll.scrollTo(
                    (selected.left - (binding.lipShadeScroll.width - selected.width) / 2).coerceAtLeast(0), 0,
                )
            }
        }
    }

    private fun selectShade(swatch: LipColorSwatch) {
        // Save the outgoing finish before changing shared product metadata.
        saveTuning()
        sharedPigment = swatch.shade.color
        sharedProductDensity = swatch.product.density
        sharedProductTexture = swatch.product.texture
        LipColorRenderingMode.values().forEach { mode ->
            sharedPigmentOpacities[mode] = swatch.product.density.initialPigmentOpacity(mode)
        }
        selectedShadeKey = swatch.key
        selectedFinish = swatch.product.finish.renderingFinish(selectedColorRenderingMode) ?: selectedFinish
        // Assign state first: the button listener sees the same finish and does not restore twice.
        updateFinishSelection()
        if (selectedFinish != LipstickFinish.TRACKING_TEST) {
            tuning = restoreTuning(selectedColorRenderingMode, selectedFinish)
                .withProductShade(swatch, selectedColorRenderingMode)
        }
        renderer.setLipstickTuning(tuning)
        renderer.setLipstickFinish(selectedFinish)
        updateTuningControls()
        updateShadeSelection()
        saveTuning()
    }

    private fun updateFinishSelection() {
        val ios = selectedColorRenderingMode == LipColorRenderingMode.IOS_REFERENCE
        binding.finishOil.visibility = if (ios) View.VISIBLE else View.GONE
        binding.finishTint.visibility = if (ios) View.VISIBLE else View.GONE
        binding.finishToggleGroup.check(when (selectedFinish) {
            LipstickFinish.MATTE -> R.id.finish_matte
            LipstickFinish.SATIN -> R.id.finish_satin
            LipstickFinish.GLOSS -> R.id.finish_gloss
            LipstickFinish.TRACKING_TEST -> R.id.finish_tracking_test
            LipstickFinish.OIL -> R.id.finish_oil
            LipstickFinish.TINT -> R.id.finish_tint
        })
    }

    private fun updateShadeSelection() {
        shadeControls.forEach { control ->
            val selected = control.key == (if (editingLiner) selectedLinerShadeId else selectedShadeKey)
            control.item.isSelected = selected
            control.selectionRing.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            control.shadeLabel.setTextColor(ContextCompat.getColor(this,
                if (selected) R.color.rose_300 else R.color.text_primary_on_dark,
            ))
        }
    }

    private fun restoreSharedSelection() {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        val hex = preferences.getInt(SHARED_PIGMENT_HEX_KEY, -1)
        sharedPigment = hex.takeIf { it in 0..0xFFFFFF }?.let(::LipstickColor)
        val storedKey = preferences.getString(SELECTED_SHADE_KEY, null)
        val selected = LipstickColorCatalog.swatches.firstOrNull {
            it.key == storedKey && it.shade.color == sharedPigment
        }
        selectedShadeKey = selected?.key
        // Existing palette selections adopt the new density rule once; no profile data is erased.
        val storedDensity = preferences.getString(SHARED_PRODUCT_DENSITY_KEY, null)
        sharedProductDensity = runCatching { LipstickDensity.valueOf(storedDensity.orEmpty()) }
            .getOrNull() ?: selected?.product?.density
        val storedTexture = preferences.getString(SHARED_PRODUCT_TEXTURE_KEY, null)
        sharedProductTexture = runCatching { LipstickTexture.valueOf(storedTexture.orEmpty()) }
            .getOrNull() ?: selected?.product?.texture
        selectedFinish = restoreFinishForMode(selectedColorRenderingMode,
            selected?.product?.finish?.renderingFinish(selectedColorRenderingMode) ?: LipstickFinish.MATTE)
        LipColorRenderingMode.values().forEach { mode ->
            val key = sharedPigmentOpacityKey(mode)
            if (preferences.contains(key)) {
                sharedPigmentOpacities[mode] = preferences.getFloat(key, 1f).coerceIn(0f, 1f)
            } else {
                sharedProductDensity?.let { sharedPigmentOpacities[mode] = it.initialPigmentOpacity(mode) }
            }
        }
    }

    private fun withSharedSelection(
        state: LipstickTuning,
        renderingMode: LipColorRenderingMode,
        finish: LipstickFinish,
    ): LipstickTuning {
        if (finish == LipstickFinish.TRACKING_TEST) return state
        val colored = sharedPigment?.let(state::withPigment) ?: state
        return colored.copy(
            productDensity = sharedProductDensity ?: colored.productDensity,
            productTexture = sharedProductTexture ?: colored.productTexture,
            pigmentOpacity = sharedPigmentOpacities[renderingMode] ?: colored.pigmentOpacity,
        )
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
            header.addView(value, LinearLayout.LayoutParams(dp(if (parameter.discrete) 96 else 58), ViewGroup.LayoutParams.WRAP_CONTENT))
            val seekBar = SeekBar(this).apply {
                max = sliderStepsFor(parameter)
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
                    if (selectedFinish != LipstickFinish.TRACKING_TEST) {
                        when (parameter) {
                            LipstickTuningParameter.PRODUCT_TEXTURE -> {
                                sharedProductTexture = tuning.productTexture
                            }
                            LipstickTuningParameter.PRODUCT_DENSITY -> {
                                sharedProductDensity = tuning.productDensity
                                LipColorRenderingMode.values().forEach { mode ->
                                    sharedPigmentOpacities[mode] = tuning.productDensity.initialPigmentOpacity(mode)
                                }
                                tuning = tuning.withProductDensity(tuning.productDensity, selectedColorRenderingMode)
                                updateTuningControls()
                            }
                            LipstickTuningParameter.PIGMENT_OPACITY -> {
                                // Fine-tuning remains shared across finishes, independently in each mode.
                                sharedPigmentOpacities[selectedColorRenderingMode] = tuning.pigmentOpacity
                            }
                            else -> Unit
                        }
                    }
                    if (selectedFinish != LipstickFinish.TRACKING_TEST &&
                        (parameter == LipstickTuningParameter.PIGMENT_RED ||
                            parameter == LipstickTuningParameter.PIGMENT_GREEN ||
                            parameter == LipstickTuningParameter.PIGMENT_BLUE)
                    ) {
                        // Manual RGB is another shared colour selection, not a stale catalogue shade.
                        sharedPigment = tuning.pigmentColor()
                        selectedShadeKey = null
                        updateShadeSelection()
                    }
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
            tuningControls += TuningControl(parameter, row, value, seekBar)
        }
        updateTuningControls()
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
            val visible = !control.parameter.unifiedOnly ||
                (selectedColorRenderingMode == LipColorRenderingMode.UNIFIED &&
                    selectedFinish != LipstickFinish.TRACKING_TEST)
            val supported = selectedColorRenderingMode != LipColorRenderingMode.IOS_REFERENCE ||
                selectedFinish == LipstickFinish.TRACKING_TEST || control.parameter.supportedInIosDev()
            control.row.visibility = if (visible && supported) View.VISIBLE else View.GONE
            val value = control.parameter.read(tuning)
            control.valueView.text = formatValue(control.parameter, value)
            control.seekBar.progress = progressFor(control.parameter, value)
        }
        updatingTuningControls = false
    }

    private fun sliderStepsFor(parameter: LipstickTuningParameter): Int =
        if (parameter.discrete) 2 else SLIDER_STEPS

    private fun progressFor(parameter: LipstickTuningParameter, value: Float): Int =
        (((value - parameter.minimum) / (parameter.maximum - parameter.minimum)) * sliderStepsFor(parameter))
            .roundToInt()
            .coerceIn(0, sliderStepsFor(parameter))

    private fun valueFor(parameter: LipstickTuningParameter, progress: Int): Float =
        parameter.minimum + (parameter.maximum - parameter.minimum) * progress / sliderStepsFor(parameter)

    private fun formatValue(parameter: LipstickTuningParameter, value: Float): String = when (parameter) {
        LipstickTuningParameter.PRODUCT_DENSITY -> LipstickDensity.values()[value.roundToInt().coerceIn(0, 2)].displayName
        LipstickTuningParameter.PRODUCT_TEXTURE -> LipstickTexture.values()[value.roundToInt().coerceIn(0, 2)].displayName
        else -> String.format(Locale.US, "%.${parameter.decimals}f", value)
    }

    private fun formatExportValue(parameter: LipstickTuningParameter, value: Float): String = when (parameter) {
        LipstickTuningParameter.PRODUCT_DENSITY -> LipstickDensity.values()[value.roundToInt().coerceIn(0, 2)].name
        LipstickTuningParameter.PRODUCT_TEXTURE -> LipstickTexture.values()[value.roundToInt().coerceIn(0, 2)].name
        else -> formatValue(parameter, value)
    }

    private fun restoreTuning(
        renderingMode: LipColorRenderingMode,
        finish: LipstickFinish,
    ): LipstickTuning {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        val defaults = LipstickTuning.defaultsFor(renderingMode, finish)
        val restored = LipstickTuningParameter.values().fold(defaults) { state, parameter ->
            val defaultValue = parameter.read(defaults)
            val storedValue = preferences.getFloat(preferenceKey(renderingMode, finish, parameter), defaultValue)
                .coerceIn(parameter.minimum, parameter.maximum)
            parameter.update(state, storedValue)
        }
        return withSharedSelection(restored, renderingMode, finish)
    }

    private fun saveTuning() {
        persistLiner()
        getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE).edit().apply {
            putString(selectedFinishKey(selectedColorRenderingMode), selectedFinish.name)
            LipstickTuningParameter.values().forEach { parameter ->
                putFloat(preferenceKey(selectedColorRenderingMode, selectedFinish, parameter), parameter.read(tuning))
            }
            sharedPigment?.let { color ->
                putInt(SHARED_PIGMENT_HEX_KEY, color.sourceSrgbHex)
                putString(SELECTED_SHADE_KEY, selectedShadeKey)
                // Synchronise source RGB; unrelated material tuning stays finish/mode-specific.
                LipColorRenderingMode.values().forEach { mode ->
                    LipstickFinish.values().filter { it != LipstickFinish.TRACKING_TEST && it.supportedIn(mode) }.forEach { finish ->
                        putFloat(preferenceKey(mode, finish, LipstickTuningParameter.PIGMENT_RED), color.red8Bit.toFloat())
                        putFloat(preferenceKey(mode, finish, LipstickTuningParameter.PIGMENT_GREEN), color.green8Bit.toFloat())
                        putFloat(preferenceKey(mode, finish, LipstickTuningParameter.PIGMENT_BLUE), color.blue8Bit.toFloat())
                    }
                }
            }
            sharedProductDensity?.let { density ->
                putString(SHARED_PRODUCT_DENSITY_KEY, density.name)
                LipColorRenderingMode.values().forEach { mode ->
                    LipstickFinish.values().filter { it != LipstickFinish.TRACKING_TEST && it.supportedIn(mode) }.forEach { finish ->
                        putFloat(preferenceKey(mode, finish, LipstickTuningParameter.PRODUCT_DENSITY), density.ordinal.toFloat())
                    }
                }
            }
            sharedProductTexture?.let { texture ->
                putString(SHARED_PRODUCT_TEXTURE_KEY, texture.name)
                LipColorRenderingMode.values().forEach { mode ->
                    LipstickFinish.values().filter { it != LipstickFinish.TRACKING_TEST && it.supportedIn(mode) }.forEach { finish ->
                        putFloat(preferenceKey(mode, finish, LipstickTuningParameter.PRODUCT_TEXTURE), texture.ordinal.toFloat())
                    }
                }
            }
            sharedPigmentOpacities.forEach { (mode, intensity) ->
                putFloat(sharedPigmentOpacityKey(mode), intensity)
                LipstickFinish.values().filter { it != LipstickFinish.TRACKING_TEST && it.supportedIn(mode) }.forEach { finish ->
                    putFloat(preferenceKey(mode, finish, LipstickTuningParameter.PIGMENT_OPACITY), intensity)
                }
            }
            apply()
        }
    }

    private fun sharedPigmentOpacityKey(mode: LipColorRenderingMode): String =
        if (mode == LipColorRenderingMode.IOS_REFERENCE) {
            "${mode.name}_shared_pigment_opacity_dev_v1"
        } else {
            "${mode.name}_shared_pigment_opacity_v1"
        }

    private fun selectedFinishKey(mode: LipColorRenderingMode): String =
        if (mode == LipColorRenderingMode.IOS_REFERENCE) IOS_SELECTED_FINISH_KEY else SELECTED_FINISH_KEY

    private fun restoreFinishForMode(mode: LipColorRenderingMode, fallback: LipstickFinish): LipstickFinish {
        val preferences = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
        fun read(key: String): LipstickFinish? = runCatching {
            LipstickFinish.valueOf(preferences.getString(key, null).orEmpty())
        }.getOrNull()?.takeIf { it.supportedIn(mode) }
        read(selectedFinishKey(mode))?.let { return it }
        // Adopt an already-selected oil/tint product once in the newly enabled iOS mode.
        val productFinish = LipstickColorCatalog.swatches.firstOrNull { it.key == selectedShadeKey }
            ?.product?.finish?.renderingFinish(mode)
        if (productFinish == LipstickFinish.OIL || productFinish == LipstickFinish.TINT) return productFinish
        return read(SELECTED_FINISH_KEY) ?: fallback.takeIf { it.supportedIn(mode) } ?: LipstickFinish.MATTE
    }

    private fun preferenceKey(
        renderingMode: LipColorRenderingMode,
        finish: LipstickFinish,
        parameter: LipstickTuningParameter,
    ): String = "${tuningProfileKey(renderingMode, finish)}_${parameter.name}"

    private fun tuningProfileKey(
        renderingMode: LipColorRenderingMode,
        finish: LipstickFinish,
    ): String = if (finish == LipstickFinish.TRACKING_TEST) {
        finish.name
    } else if (renderingMode == LipColorRenderingMode.IOS_REFERENCE) {
        // Replace only the disposable old iOS formula, retaining every UNIFIED key/value.
        "${renderingMode.name}_MATERIAL_${finish.name}_DEV_42318424_V1"
    } else {
        // Adopt the explicitly requested common calibration once, without deleting old profiles.
        "${renderingMode.name}_MATERIAL_${finish.name}_COMMON_9E1F23_V1"
    }

    private fun restoreColorRenderingMode(): LipColorRenderingMode {
        val stored = getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
            .getString(COLOR_RENDERING_MODE_KEY, null)
        return runCatching { LipColorRenderingMode.valueOf(stored.orEmpty()) }
            .getOrDefault(LipColorRenderingMode.IOS_REFERENCE)
    }

    private fun saveColorRenderingMode() {
        getSharedPreferences(TUNING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(COLOR_RENDERING_MODE_KEY, selectedColorRenderingMode.name)
            .apply()
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
        const val LIPSTICK_ENABLED_KEY = "lipstick_enabled_v1"
        const val LOWER_LIP_EXTENSION_PIXELS_KEY = "lower_lip_outer_extension_pixels_v1"
        const val LOWER_LIP_EXTENSION_4PX_ADOPTED_KEY = "lower_lip_extension_4px_adopted_v1"
        const val LIP_CONTOUR_POINTS_KEY = "lip_contour_points_v1"
        const val COLOR_RENDERING_MODE_KEY = "color_rendering_mode"
        const val SHARED_PIGMENT_HEX_KEY = "shared_pigment_srgb_hex_v1"
        const val SELECTED_SHADE_KEY = "selected_lip_shade_key_v1"
        const val SHARED_PRODUCT_DENSITY_KEY = "shared_product_density_v1"
        const val SHARED_PRODUCT_TEXTURE_KEY = "shared_product_texture_v1"
        const val SELECTED_FINISH_KEY = "selected_lipstick_finish_v1"
        const val IOS_SELECTED_FINISH_KEY = "IOS_REFERENCE_selected_lipstick_finish_v1"
    }
}
