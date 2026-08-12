package com.example.armakeup.render

import android.hardware.HardwareBuffer

/** Immutable camera transform captured for the exact buffer submitted to the GPU. */
internal class VulkanCameraTransform(
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val rotationDegrees: Int,
    val mirrorHorizontal: Boolean,
    matrix: FloatArray,
) {
    private val uvTransform = matrix.copyOf()

    init {
        require(uvTransform.size == MATRIX_ELEMENT_COUNT)
        require(rotationDegrees in SUPPORTED_ROTATIONS)
        require(cropRight >= cropLeft)
        require(cropBottom >= cropTop)
    }

    fun matrixCopy(): FloatArray = uvTransform.copyOf()

    companion object {
        private const val MATRIX_ELEMENT_COUNT = 16
        private val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

/**
 * One zero-copy camera buffer and all state needed to interpret it.
 *
 * The HardwareBuffer remains owned by the native ImageReader. The Java reference is closed only
 * after Filament's release callback, while [nativeToken] releases the matching native AImage.
 */
internal data class VulkanCameraFrameState(
    val hardwareBuffer: HardwareBuffer,
    val nativeToken: Long,
    val sensorTimestampNs: Long,
    val width: Int,
    val height: Int,
    val hardwareBufferFormat: Int,
    val hardwareBufferUsage: Long,
    val transform: VulkanCameraTransform,
    val landmarkSensorTimestampNs: Long?,
    val acquireFenceImported: Boolean,
    val releaseFenceExported: Boolean,
) {
    val landmarkAgeNs: Long?
        get() = landmarkSensorTimestampNs?.let { landmarkTimestamp ->
            cameraToLandmarkAgeNs(sensorTimestampNs, landmarkTimestamp)
        }

    companion object {
        internal fun cameraToLandmarkAgeNs(
            cameraSensorTimestampNs: Long,
            landmarkSensorTimestampNs: Long,
        ): Long = (cameraSensorTimestampNs - landmarkSensorTimestampNs).coerceAtLeast(0L)
    }
}
