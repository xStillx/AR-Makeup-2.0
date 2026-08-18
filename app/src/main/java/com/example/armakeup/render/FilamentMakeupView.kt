package com.example.armakeup.render

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import androidx.camera.core.Preview
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.tracking.LandmarkRenderFrame
import com.example.armakeup.tracking.CameraProjectionCalibration
import com.example.armakeup.tracking.TrackingTelemetrySink
import com.google.android.filament.Filament

/**
 * CameraX input and makeup output share this Filament-owned surface.
 *
 * The view intentionally exposes only render-domain operations. Camera lifecycle and ML inference
 * remain owned by MainActivity and FaceLandmarkerTracker respectively.
 */
class FilamentMakeupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    private var errorListener: ((String) -> Unit)? = null
    private var initializationError: Throwable? = null
    private var compositor: MakeupRendererController? = null

    /** Must be called once after inflation and before CameraX requests its input surface. */
    fun initializeRenderer(
        displayQueueProtectionEnabled: Boolean?,
        filamentPresentationHintsEnabled: Boolean,
        nativeVulkanVisibleEnabled: Boolean,
    ) {
        if (isInEditMode || compositor != null || initializationError != null) return
        compositor = runCatching {
            if (nativeVulkanVisibleEnabled) {
                NativeVulkanVisibleRenderer(
                    context = context,
                    surfaceView = this,
                    onError = ::dispatchError,
                )
            } else {
                FilamentMakeupRenderer(
                    context = context,
                    surfaceView = this,
                    displayQueueProtectionOverride = displayQueueProtectionEnabled,
                    filamentPresentationHintsEnabled = filamentPresentationHintsEnabled,
                    onError = ::dispatchError,
                )
            }
        }.onFailure { initializationError = it }.getOrNull()
    }

    val surfaceProvider = Preview.SurfaceProvider { request ->
        val activeCompositor = compositor
        if (activeCompositor == null) {
            request.willNotProvideSurface()
            initializationError?.let(::dispatchError)
        } else {
            activeCompositor.onSurfaceRequested(request)
        }
    }

    val initializationErrorMessage: String?
        get() = initializationError?.message

    val renderBackendLabel: String
        get() = compositor?.let { context.getString(it.renderBackendLabelRes) }
            ?: context.getString(com.example.armakeup.R.string.render_backend_unavailable)

    fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
        initializationError?.let(::dispatchError)
    }

    fun setTrackingTelemetrySink(sink: TrackingTelemetrySink?) {
        compositor?.setTrackingTelemetrySink(sink)
    }

    fun setCameraProjectionCalibration(calibration: CameraProjectionCalibration?) {
        compositor?.setCameraProjectionCalibration(calibration)
    }

    fun setGyroscopeCorrectionEnabled(enabled: Boolean) {
        compositor?.setGyroscopeCorrectionEnabled(enabled)
    }

    fun setResult(
        landmarks: LandmarkRenderFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        sensorTimestampNs: Long,
    ) {
        compositor?.setResult(
            landmarks,
            sourceWidth,
            sourceHeight,
            rotationDegrees,
            mirrorHorizontal,
            sensorTimestampNs,
        )
    }

    fun clear() {
        compositor?.clear()
    }

    internal fun setLipstickFinish(finish: LipstickFinish) {
        compositor?.setLipstickFinish(finish)
    }

    fun onResumeRenderer() {
        compositor?.resume()
    }

    fun onPauseRenderer() {
        compositor?.pause()
    }

    fun destroyRenderer() {
        compositor?.destroy()
    }

    private fun dispatchError(error: Throwable) {
        dispatchError(error.message ?: error.javaClass.simpleName)
    }

    private fun dispatchError(message: String) {
        errorListener?.invoke(message)
    }

    companion object {
        init {
            Filament.init()
        }
    }
}
