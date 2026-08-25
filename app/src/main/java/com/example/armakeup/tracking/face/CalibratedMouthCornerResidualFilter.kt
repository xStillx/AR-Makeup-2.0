package com.example.armakeup.tracking.face

import kotlin.math.sqrt

internal data class MouthCornerResidualPair(
    val firstX: Float,
    val firstY: Float,
    val secondX: Float,
    val secondY: Float,
) {
    fun maximumMagnitude(): Float = maxOf(
        sqrt(firstX * firstX + firstY * firstY),
        sqrt(secondX * secondX + secondY * secondY),
    )

    companion object {
        val ZERO = MouthCornerResidualPair(0f, 0f, 0f, 0f)
    }
}

/**
 * Extracts intentional mouth-corner expression from the systematic MediaPipe/ARCore mismatch.
 *
 * Inputs and outputs are normalized to the current ARCore mouth basis. State advances only when a
 * new MediaPipe timestamp arrives, so the same local observation cannot pulse between render
 * frames. The initial samples establish the per-face neutral mismatch; quiet samples then adapt it
 * slowly while pronounced expression freezes calibration and receives a fast response.
 */
internal class CalibratedMouthCornerResidualFilter(
    private val calibrationSampleCount: Int = DEFAULT_CALIBRATION_SAMPLE_COUNT,
    private val activationRadius: Float = DEFAULT_ACTIVATION_RADIUS,
    private val releaseRadius: Float = DEFAULT_RELEASE_RADIUS,
    private val neutralAdaptation: Float = DEFAULT_NEUTRAL_ADAPTATION,
    private val activeResponse: Float = DEFAULT_ACTIVE_RESPONSE,
    private val maximumCornerMagnitude: Float = DEFAULT_MAXIMUM_CORNER_MAGNITUDE,
    private val resetGapNs: Long = DEFAULT_RESET_GAP_NS,
) {
    private var lastTimestampNs = NO_TIMESTAMP
    private var calibrationSamples = 0
    private var baseline = MouthCornerResidualPair.ZERO
    private var output = MouthCornerResidualPair.ZERO
    private var commonModeActive = false
    private var differentialModeActive = false

    init {
        require(calibrationSampleCount > 0)
        require(releaseRadius.isFinite() && releaseRadius >= 0f)
        require(activationRadius.isFinite() && activationRadius > releaseRadius)
        require(neutralAdaptation.isFinite() && neutralAdaptation in 0f..1f)
        require(activeResponse.isFinite() && activeResponse in 0f..1f)
        require(maximumCornerMagnitude.isFinite() && maximumCornerMagnitude > activationRadius)
        require(resetGapNs > 0L)
    }

    fun update(timestampNs: Long, measurement: MouthCornerResidualPair): MouthCornerResidualPair {
        require(timestampNs >= 0L)
        if (timestampNs <= lastTimestampNs) return output
        if (lastTimestampNs != NO_TIMESTAMP && timestampNs - lastTimestampNs > resetGapNs) reset()
        lastTimestampNs = timestampNs

        if (calibrationSamples < calibrationSampleCount) {
            calibrationSamples++
            val alpha = 1f / calibrationSamples
            baseline = interpolate(baseline, measurement, alpha)
            output = MouthCornerResidualPair.ZERO
            return output
        }

        val expression = subtract(measurement, baseline)
        val common = mode(
            x = (expression.firstX + expression.secondX) * 0.5f,
            y = (expression.firstY + expression.secondY) * 0.5f,
            wasActive = commonModeActive,
        )
        commonModeActive = common.active
        val differential = mode(
            x = (expression.secondX - expression.firstX) * 0.5f,
            y = (expression.secondY - expression.firstY) * 0.5f,
            wasActive = differentialModeActive,
        )
        differentialModeActive = differential.active

        if (!common.active && !differential.active) {
            baseline = interpolate(baseline, measurement, neutralAdaptation)
        }

        val target = if (!common.active && !differential.active) {
            MouthCornerResidualPair.ZERO
        } else {
            clamp(
                MouthCornerResidualPair(
                    firstX = common.x - differential.x,
                    firstY = common.y - differential.y,
                    secondX = common.x + differential.x,
                    secondY = common.y + differential.y,
                ),
            )
        }
        output = interpolate(output, target, activeResponse)
        if (target == MouthCornerResidualPair.ZERO && output.maximumMagnitude() < OUTPUT_EPSILON) {
            output = MouthCornerResidualPair.ZERO
        }
        return output
    }

    fun reset() {
        lastTimestampNs = NO_TIMESTAMP
        calibrationSamples = 0
        baseline = MouthCornerResidualPair.ZERO
        output = MouthCornerResidualPair.ZERO
        commonModeActive = false
        differentialModeActive = false
    }

    private fun mode(x: Float, y: Float, wasActive: Boolean): Mode {
        val magnitude = sqrt(x * x + y * y)
        val active = if (wasActive) magnitude > releaseRadius else magnitude >= activationRadius
        if (!active || magnitude <= releaseRadius) return Mode.ZERO
        val scale = (magnitude - releaseRadius) / magnitude
        return Mode(x * scale, y * scale, true)
    }

    private fun clamp(value: MouthCornerResidualPair): MouthCornerResidualPair {
        fun scale(x: Float, y: Float): Float {
            val magnitude = sqrt(x * x + y * y)
            return if (magnitude > maximumCornerMagnitude) maximumCornerMagnitude / magnitude else 1f
        }
        val firstScale = scale(value.firstX, value.firstY)
        val secondScale = scale(value.secondX, value.secondY)
        return MouthCornerResidualPair(
            value.firstX * firstScale,
            value.firstY * firstScale,
            value.secondX * secondScale,
            value.secondY * secondScale,
        )
    }

    private fun subtract(
        value: MouthCornerResidualPair,
        origin: MouthCornerResidualPair,
    ) = MouthCornerResidualPair(
        value.firstX - origin.firstX,
        value.firstY - origin.firstY,
        value.secondX - origin.secondX,
        value.secondY - origin.secondY,
    )

    private fun interpolate(
        from: MouthCornerResidualPair,
        to: MouthCornerResidualPair,
        alpha: Float,
    ) = MouthCornerResidualPair(
        from.firstX + (to.firstX - from.firstX) * alpha,
        from.firstY + (to.firstY - from.firstY) * alpha,
        from.secondX + (to.secondX - from.secondX) * alpha,
        from.secondY + (to.secondY - from.secondY) * alpha,
    )

    private data class Mode(val x: Float, val y: Float, val active: Boolean) {
        companion object {
            val ZERO = Mode(0f, 0f, false)
        }
    }

    private companion object {
        const val NO_TIMESTAMP = -1L
        const val DEFAULT_CALIBRATION_SAMPLE_COUNT = 8
        const val DEFAULT_ACTIVATION_RADIUS = 0.03f
        const val DEFAULT_RELEASE_RADIUS = 0.015f
        const val DEFAULT_NEUTRAL_ADAPTATION = 0.025f
        const val DEFAULT_ACTIVE_RESPONSE = 0.85f
        const val DEFAULT_MAXIMUM_CORNER_MAGNITUDE = 0.12f
        const val DEFAULT_RESET_GAP_NS = 500_000_000L
        const val OUTPUT_EPSILON = 1e-4f
    }
}
