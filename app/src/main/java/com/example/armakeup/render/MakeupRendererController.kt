package com.example.armakeup.render

import androidx.annotation.StringRes
import androidx.camera.core.SurfaceRequest
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.tracking.CameraProjectionCalibration
import com.example.armakeup.tracking.LandmarkRenderFrame
import com.example.armakeup.tracking.TrackingTelemetrySink

/** Lifecycle and camera boundary shared by the Filament baseline and native Vulkan proof. */
internal interface MakeupRendererController {
    @get:StringRes
    val renderBackendLabelRes: Int

    fun onSurfaceRequested(request: SurfaceRequest)

    fun setTrackingTelemetrySink(sink: TrackingTelemetrySink?)

    fun setCameraProjectionCalibration(calibration: CameraProjectionCalibration?)

    fun setGyroscopeCorrectionEnabled(enabled: Boolean)

    fun setResult(
        landmarks: LandmarkRenderFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        sensorTimestampNs: Long,
    )

    fun clear()

    fun setLipstickFinish(finish: LipstickFinish)

    fun resume()

    fun pause()

    fun destroy()
}
