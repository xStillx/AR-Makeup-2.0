package com.example.armakeup.render

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Display-space ROI consumed by the Vulkan temporal tracker. UV origin is top-left. */
internal data class VulkanTemporalTrackingRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right - left >= MIN_EXTENT && bottom - top >= MIN_EXTENT &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f

    fun toFloatArray(): FloatArray = floatArrayOf(left, top, right, bottom)

    companion object {
        val INVALID = VulkanTemporalTrackingRoi(0f, 0f, 0f, 0f)
        private const val MIN_EXTENT = 0.01f
    }
}

/** One robust similarity transform measured between consecutive camera sensor timestamps. */
internal data class VulkanTemporalTrackingResult(
    val fromSensorTimestampNs: Long,
    val toSensorTimestampNs: Long,
    /** x' = scaleCos*x - scaleSin*y + translationX. */
    val scaleCos: Float,
    /** y' = scaleSin*x + scaleCos*y + translationY. */
    val scaleSin: Float,
    val translationX: Float,
    val translationY: Float,
    val confidence: Float,
    val rmsResidual: Float,
    val inlierCount: Int,
) {
    val durationNs: Long
        get() = toSensorTimestampNs - fromSensorTimestampNs

    val scale: Float
        get() = hypot(scaleCos, scaleSin)

    val rotationRadians: Float
        get() = atan2(scaleSin, scaleCos)

    val translationMagnitude: Float
        get() = hypot(translationX, translationY)

    val passesNativeContract: Boolean
        get() = fromSensorTimestampNs > 0L && durationNs in 1L..MAX_INTERVAL_NS &&
            scaleCos.isFinite() && scaleSin.isFinite() &&
            translationX.isFinite() && translationY.isFinite() &&
            confidence.isFinite() && rmsResidual.isFinite() &&
            confidence >= MIN_CONFIDENCE && rmsResidual <= MAX_RMS_RESIDUAL &&
            inlierCount >= MIN_INLIERS && scale in MIN_SCALE..MAX_SCALE &&
            abs(rotationRadians) <= MAX_ROTATION_RADIANS &&
            translationMagnitude <= MAX_TRANSLATION

    companion object {
        internal const val VALUE_COUNT = 8
        internal const val MIN_CONFIDENCE = 0.42f
        internal const val MAX_RMS_RESIDUAL = 0.012f
        internal const val MIN_INLIERS = 16
        private const val MAX_INTERVAL_NS = 80_000_000L
        // Per-frame capture bounds. Reliability is established independently by the native
        // robust fit (confidence, residual, inlier count and spatial coverage).
        private const val MIN_SCALE = 0.94f
        private const val MAX_SCALE = 1.06f
        private const val MAX_ROTATION_RADIANS = 0.10f
        private const val MAX_TRANSLATION = 0.10f

        fun fromNative(
            fromSensorTimestampNs: Long,
            toSensorTimestampNs: Long,
            values: FloatArray,
        ): VulkanTemporalTrackingResult? {
            if (values.size < VALUE_COUNT || values[VALID_INDEX] < 0.5f) return null
            return VulkanTemporalTrackingResult(
                fromSensorTimestampNs = fromSensorTimestampNs,
                toSensorTimestampNs = toSensorTimestampNs,
                scaleCos = values[SCALE_COS_INDEX],
                scaleSin = values[SCALE_SIN_INDEX],
                translationX = values[TRANSLATION_X_INDEX],
                translationY = values[TRANSLATION_Y_INDEX],
                confidence = values[CONFIDENCE_INDEX],
                rmsResidual = values[RMS_RESIDUAL_INDEX],
                inlierCount = values[INLIER_COUNT_INDEX].toInt(),
            ).takeIf { it.passesNativeContract }
        }

        private const val SCALE_COS_INDEX = 0
        private const val SCALE_SIN_INDEX = 1
        private const val TRANSLATION_X_INDEX = 2
        private const val TRANSLATION_Y_INDEX = 3
        private const val CONFIDENCE_INDEX = 4
        private const val RMS_RESIDUAL_INDEX = 5
        private const val INLIER_COUNT_INDEX = 6
        private const val VALID_INDEX = 7
    }
}
