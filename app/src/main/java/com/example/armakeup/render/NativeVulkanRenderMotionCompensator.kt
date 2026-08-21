package com.example.armakeup.render

import com.example.armakeup.tracking.GyroscopeLipCompensator
import com.example.armakeup.tracking.LandmarkRenderFrame
import kotlin.math.hypot

/**
 * Adds a small render-only residual lead for coherent head motion on the native visible path.
 *
 * The tracker remains unchanged. Device rotation gates the lead because camera motion is already
 * aligned to the retained camera buffer by [GyroscopeLipCompensator]; extrapolating the landmark
 * velocity at the same time would double-compensate it. Slow motion and stationary tremor remain
 * on the proven V6.2 predictor path.
 */
internal class NativeVulkanRenderMotionCompensator(
    private val maximumAdditionalPredictionSeconds: Float =
        DEFAULT_MAXIMUM_ADDITIONAL_PREDICTION_SECONDS,
    private val motionStartSpeed: Float = DEFAULT_MOTION_START_SPEED,
    private val fullMotionSpeed: Float = DEFAULT_FULL_MOTION_SPEED,
    private val gyroscopeGateStartRadians: Float = DEFAULT_GYROSCOPE_GATE_START_RADIANS,
    private val gyroscopeGateFullRadians: Float = DEFAULT_GYROSCOPE_GATE_FULL_RADIANS,
) {
    data class Prediction(
        val baseSeconds: Float,
        val additionalSeconds: Float,
        val cameraMotionSeconds: Float,
    ) {
        val totalSeconds: Float
            get() = baseSeconds + additionalSeconds
    }

    init {
        require(maximumAdditionalPredictionSeconds >= 0f)
        require(motionStartSpeed >= 0f && fullMotionSpeed > motionStartSpeed)
        require(gyroscopeGateStartRadians >= 0f)
        require(gyroscopeGateFullRadians > gyroscopeGateStartRadians)
    }

    fun predictionFor(
        landmarks: LandmarkRenderFrame,
        renderTimestampMs: Long,
        baseGyroscopeCorrection: GyroscopeLipCompensator.Correction,
    ): Prediction {
        val baseSeconds = landmarks.predictionSeconds(renderTimestampMs)
        val coverage = landmarks.globalPredictionCoverage()
        val motionGain = smoothRange(
            landmarks.globalVelocityMagnitude(),
            motionStartSpeed,
            fullMotionSpeed,
        )
        val gyroscopeRotation = if (baseGyroscopeCorrection.applied) {
            hypot(
                hypot(
                    baseGyroscopeCorrection.rotationX,
                    baseGyroscopeCorrection.rotationY,
                ),
                baseGyroscopeCorrection.rotationZ,
            )
        } else {
            0f
        }
        val headMotionGain = 1f - smoothRange(
            gyroscopeRotation,
            gyroscopeGateStartRadians,
            gyroscopeGateFullRadians,
        )
        val additionalSeconds = maximumAdditionalPredictionSeconds *
            coverage * motionGain * headMotionGain
        return Prediction(
            baseSeconds = baseSeconds,
            additionalSeconds = additionalSeconds,
            cameraMotionSeconds = landmarks.cameraMotionPredictionSeconds(renderTimestampMs) +
                additionalSeconds * coverage,
        )
    }

    private fun smoothRange(value: Float, start: Float, end: Float): Float {
        val linear = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return linear * linear * (3f - 2f * linear)
    }

    companion object {
        private const val DEFAULT_MAXIMUM_ADDITIONAL_PREDICTION_SECONDS = 0.032f
        private const val DEFAULT_MOTION_START_SPEED = 0.10f
        private const val DEFAULT_FULL_MOTION_SPEED = 0.40f
        private const val DEFAULT_GYROSCOPE_GATE_START_RADIANS = 0.0015f
        private const val DEFAULT_GYROSCOPE_GATE_FULL_RADIANS = 0.008f
    }
}
