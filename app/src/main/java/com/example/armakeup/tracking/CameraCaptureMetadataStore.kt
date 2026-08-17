package com.example.armakeup.tracking

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/** Bounded timestamp lookup joining Camera2 exposure metadata to the ImageAnalysis frame. */
class CameraCaptureMetadataStore : CameraCaptureSession.CaptureCallback() {
    private val entries = LinkedHashMap<Long, CameraCaptureMetadata>()

    @Synchronized
    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        entries[sensorTimestampNs] = CameraCaptureMetadata(
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L,
            sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: -1,
            frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1L,
            rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: -1L,
            aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1,
        )
        while (entries.size > MAXIMUM_ENTRY_COUNT) {
            val oldestKey = entries.keys.firstOrNull() ?: break
            entries.remove(oldestKey)
        }
    }

    @Synchronized
    internal fun consume(sensorTimestampNs: Long): CameraCaptureMetadata {
        val exact = entries.remove(sensorTimestampNs)
        val staleBefore = sensorTimestampNs - MAXIMUM_METADATA_AGE_NS
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key < staleBefore) iterator.remove()
        }
        return exact ?: CameraCaptureMetadata.UNKNOWN
    }

    @Synchronized
    internal fun clear() {
        entries.clear()
    }

    companion object {
        private const val MAXIMUM_ENTRY_COUNT = 180
        private const val MAXIMUM_METADATA_AGE_NS = 2_000_000_000L
    }
}

internal data class CameraCaptureMetadata(
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val frameDurationNs: Long,
    val rollingShutterSkewNs: Long,
    val aeState: Int,
) {
    companion object {
        val UNKNOWN = CameraCaptureMetadata(-1L, -1, -1L, -1L, -1)
    }
}
