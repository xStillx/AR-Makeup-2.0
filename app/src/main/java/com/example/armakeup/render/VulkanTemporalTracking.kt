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

internal data class VulkanFlowSample(
    val deltaX: Float,
    val deltaY: Float,
    val confidence: Float,
    val residual: Float,
)

/**
 * Dense-enough camera-space motion measured between two consecutive camera frames.
 *
 * The grid is defined over the source-frame ROI. Vectors are normalized display-space deltas,
 * so a semantic point is propagated by adding the sampled delta to its current position.
 */
internal class VulkanMouthFlowFrame private constructor(
    val fromSensorTimestampNs: Long,
    val toSensorTimestampNs: Long,
    val sourceRoi: VulkanTemporalTrackingRoi,
    private val vectors: FloatArray,
) {
    fun sample(x: Float, y: Float): VulkanFlowSample? {
        if (!sourceRoi.isValid || !x.isFinite() || !y.isFinite()) return null
        if (x !in sourceRoi.left..sourceRoi.right || y !in sourceRoi.top..sourceRoi.bottom) {
            return null
        }
        val roiWidth = sourceRoi.right - sourceRoi.left
        val roiHeight = sourceRoi.bottom - sourceRoi.top
        val gridX = ((x - sourceRoi.left) / roiWidth * GRID_WIDTH - 0.5f)
            .coerceIn(0f, (GRID_WIDTH - 1).toFloat())
        val gridY = ((y - sourceRoi.top) / roiHeight * GRID_HEIGHT - 0.5f)
            .coerceIn(0f, (GRID_HEIGHT - 1).toFloat())
        val x0 = gridX.toInt()
        val y0 = gridY.toInt()
        val x1 = (x0 + 1).coerceAtMost(GRID_WIDTH - 1)
        val y1 = (y0 + 1).coerceAtMost(GRID_HEIGHT - 1)
        val tx = gridX - x0
        val ty = gridY - y0
        return VulkanFlowSample(
            deltaX = bilinear(x0, y0, x1, y1, tx, ty, DELTA_X_OFFSET),
            deltaY = bilinear(x0, y0, x1, y1, tx, ty, DELTA_Y_OFFSET),
            confidence = bilinear(x0, y0, x1, y1, tx, ty, CONFIDENCE_OFFSET)
                .coerceIn(0f, 1f),
            residual = bilinear(x0, y0, x1, y1, tx, ty, RESIDUAL_OFFSET)
                .coerceAtLeast(0f),
        )
    }

    private fun bilinear(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        tx: Float,
        ty: Float,
        component: Int,
    ): Float {
        val top = lerp(vector(x0, y0, component), vector(x1, y0, component), tx)
        val bottom = lerp(vector(x0, y1, component), vector(x1, y1, component), tx)
        return lerp(top, bottom, ty)
    }

    private fun vector(x: Int, y: Int, component: Int): Float =
        vectors[(y * GRID_WIDTH + x) * VECTOR_COMPONENT_COUNT + component]

    companion object {
        internal const val GRID_WIDTH = 12
        internal const val GRID_HEIGHT = 9
        internal const val VECTOR_COMPONENT_COUNT = 4
        internal const val VALUE_COUNT =
            VulkanTemporalTrackingRoiElementCount.VALUE_COUNT +
                GRID_WIDTH * GRID_HEIGHT * VECTOR_COMPONENT_COUNT
        private const val DELTA_X_OFFSET = 0
        private const val DELTA_Y_OFFSET = 1
        private const val CONFIDENCE_OFFSET = 2
        private const val RESIDUAL_OFFSET = 3

        fun fromNative(metadata: LongArray, values: FloatArray): VulkanMouthFlowFrame? {
            require(metadata.size >= 3)
            require(values.size >= VALUE_COUNT)
            val fromTimestampNs = metadata[1]
            val toTimestampNs = metadata[2]
            if (fromTimestampNs <= 0L || toTimestampNs <= fromTimestampNs) return null
            val roi = VulkanTemporalTrackingRoi(
                left = values[0],
                top = values[1],
                right = values[2],
                bottom = values[3],
            )
            if (!roi.isValid) return null
            val flow = values.copyOfRange(
                VulkanTemporalTrackingRoiElementCount.VALUE_COUNT,
                VALUE_COUNT,
            )
            if (!flow.all(Float::isFinite)) return null
            return VulkanMouthFlowFrame(fromTimestampNs, toTimestampNs, roi, flow)
        }

        private fun lerp(first: Float, second: Float, weight: Float): Float =
            first + (second - first) * weight
    }
}

private object VulkanTemporalTrackingRoiElementCount {
    const val VALUE_COUNT = 4
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
