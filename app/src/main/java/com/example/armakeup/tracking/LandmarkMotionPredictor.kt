package com.example.armakeup.tracking

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * An adaptive low-pass filter with a short, bounded motion prediction.
 *
 * Small landmark changes are smoothed strongly to suppress camera and ML noise. Coherent motion
 * of the whole face gets a fast translation path, while local landmark deformation remains more
 * filtered. Prediction uses filtered derivatives and dead zones so stationary landmarks cannot
 * drift on their own.
 */
class LandmarkMotionPredictor(
    private val minCutoffHz: Float = DEFAULT_MIN_CUTOFF_HZ,
    private val maxCutoffHz: Float = DEFAULT_MAX_CUTOFF_HZ,
    private val faceSpeedCoefficient: Float = DEFAULT_FACE_SPEED_COEFFICIENT,
    private val localSpeedCoefficient: Float = DEFAULT_LOCAL_SPEED_COEFFICIENT,
    private val derivativeCutoffHz: Float = DEFAULT_DERIVATIVE_CUTOFF_HZ,
    private val globalVelocityCutoffHz: Float = DEFAULT_GLOBAL_VELOCITY_CUTOFF_HZ,
    private val globalVelocityDeadZone: Float = DEFAULT_GLOBAL_VELOCITY_DEAD_ZONE,
    private val velocityDeadZone: Float = DEFAULT_VELOCITY_DEAD_ZONE,
    private val maxVelocityPerSecond: Float = DEFAULT_MAX_VELOCITY_PER_SECOND,
    private val renderLeadMs: Long = DEFAULT_RENDER_LEAD_MS,
    private val maxPredictionMs: Long = DEFAULT_MAX_PREDICTION_MS,
    private val holdAfterLossMs: Long = DEFAULT_HOLD_AFTER_LOSS_MS,
    private val resetGapMs: Long = DEFAULT_RESET_GAP_MS,
    private val maxCentroidJump: Float = DEFAULT_MAX_CENTROID_JUMP,
) {
    private var positions = FloatArray(0)
    private var lastMeasurements = FloatArray(0)
    private var derivativeEstimates = FloatArray(0)
    private var velocities = FloatArray(0)
    private var filteredCentroidSpeed = 0f
    private var filteredCentroidVelocityX = 0f
    private var filteredCentroidVelocityY = 0f
    private var measuredCentroidVelocityX = 0f
    private var measuredCentroidVelocityY = 0f
    private var lastMeasurementTimestampMs = NO_TIMESTAMP

    init {
        require(minCutoffHz > 0f)
        require(maxCutoffHz >= minCutoffHz)
        require(faceSpeedCoefficient >= 0f)
        require(localSpeedCoefficient >= 0f)
        require(derivativeCutoffHz > 0f)
        require(globalVelocityCutoffHz > 0f)
        require(globalVelocityDeadZone >= 0f)
        require(velocityDeadZone >= 0f)
        require(maxVelocityPerSecond > 0f)
        require(renderLeadMs >= 0L)
        require(maxPredictionMs >= 0L)
        require(holdAfterLossMs >= maxPredictionMs)
        require(resetGapMs > 0L)
        require(maxCentroidJump > 0f)
    }

    /** Coordinates are interleaved as x, y, z for every landmark. */
    fun update(measurements: FloatArray, timestampMs: Long): LandmarkRenderFrame {
        require(
            measurements.isNotEmpty() &&
                measurements.size % LandmarkRenderFrame.COORDINATE_COUNT == 0,
        )

        val elapsedMs = timestampMs - lastMeasurementTimestampMs
        if (
            positions.size != measurements.size ||
            lastMeasurementTimestampMs == NO_TIMESTAMP ||
            elapsedMs <= 0L ||
            elapsedMs > resetGapMs ||
            hasImplausibleCentroidJump(measurements, elapsedMs)
        ) {
            initialize(measurements, timestampMs)
            return snapshot(predictedOnly = false)
        }

        val elapsedSeconds = elapsedMs / MILLIS_PER_SECOND
        val derivativeAlpha = smoothingAlpha(derivativeCutoffHz, elapsedSeconds)
        val stopAlpha = smoothingAlpha(STOP_RESPONSE_CUTOFF_HZ, elapsedSeconds)
        calculateMeasuredCentroidVelocity(measurements, elapsedSeconds)
        val measuredCentroidSpeed = sqrt(
            measuredCentroidVelocityX * measuredCentroidVelocityX +
                measuredCentroidVelocityY * measuredCentroidVelocityY,
        )
        val effectiveCentroidSpeed = (
            measuredCentroidSpeed - globalVelocityDeadZone
        ).coerceAtLeast(0f)
        filteredCentroidSpeed = if (effectiveCentroidSpeed > filteredCentroidSpeed) {
            effectiveCentroidSpeed
        } else {
            filteredCentroidSpeed + derivativeAlpha * (
                effectiveCentroidSpeed - filteredCentroidSpeed
            )
        }
        val faceCutoffHz = (
            minCutoffHz + faceSpeedCoefficient * filteredCentroidSpeed
        ).coerceAtMost(maxCutoffHz)
        val centroidVelocityScale = if (measuredCentroidSpeed > 0f) {
            effectiveCentroidSpeed / measuredCentroidSpeed
        } else {
            0f
        }
        val targetCentroidVelocityX = measuredCentroidVelocityX * centroidVelocityScale
        val targetCentroidVelocityY = measuredCentroidVelocityY * centroidVelocityScale
        val globalVelocityAlpha = smoothingAlpha(globalVelocityCutoffHz, elapsedSeconds)
        filteredCentroidVelocityX += globalVelocityAlpha * (
            targetCentroidVelocityX - filteredCentroidVelocityX
        )
        filteredCentroidVelocityY += globalVelocityAlpha * (
            targetCentroidVelocityY - filteredCentroidVelocityY
        )

        for (index in measurements.indices) {
            val rawDerivative = (measurements[index] - lastMeasurements[index]) / elapsedSeconds
            val centroidDerivative = when (index % LandmarkRenderFrame.COORDINATE_COUNT) {
                X_OFFSET -> measuredCentroidVelocityX
                Y_OFFSET -> measuredCentroidVelocityY
                else -> 0f
            }
            val rawLocalDerivative = rawDerivative - centroidDerivative
            val localDerivativeAlpha = if (
                rawLocalDerivative * derivativeEstimates[index] <= 0f
            ) {
                stopAlpha
            } else {
                derivativeAlpha
            }
            derivativeEstimates[index] += localDerivativeAlpha * (
                rawLocalDerivative - derivativeEstimates[index]
            )
            val localCutoffHz = (
                minCutoffHz + localSpeedCoefficient * abs(derivativeEstimates[index])
            ).coerceAtMost(maxCutoffHz)
            val positionAlpha = smoothingAlpha(
                cutoffHz = max(faceCutoffHz, localCutoffHz),
                elapsedSeconds = elapsedSeconds,
            )
            positions[index] += positionAlpha * (measurements[index] - positions[index])
            val filteredCentroidVelocity = when (
                index % LandmarkRenderFrame.COORDINATE_COUNT
            ) {
                X_OFFSET -> filteredCentroidVelocityX
                Y_OFFSET -> filteredCentroidVelocityY
                else -> 0f
            }
            velocities[index] = (
                filteredCentroidVelocity + applyDeadZone(derivativeEstimates[index])
            )
                .coerceIn(-maxVelocityPerSecond, maxVelocityPerSecond)
            lastMeasurements[index] = measurements[index]
        }
        lastMeasurementTimestampMs = timestampMs
        return snapshot(predictedOnly = false)
    }

    fun predictWithoutMeasurement(nowMs: Long): LandmarkRenderFrame? {
        if (lastMeasurementTimestampMs == NO_TIMESTAMP) return null
        if (nowMs - lastMeasurementTimestampMs > holdAfterLossMs) {
            reset()
            return null
        }
        return snapshot(predictedOnly = true)
    }

    fun reset() {
        positions = FloatArray(0)
        lastMeasurements = FloatArray(0)
        derivativeEstimates = FloatArray(0)
        velocities = FloatArray(0)
        filteredCentroidSpeed = 0f
        filteredCentroidVelocityX = 0f
        filteredCentroidVelocityY = 0f
        measuredCentroidVelocityX = 0f
        measuredCentroidVelocityY = 0f
        lastMeasurementTimestampMs = NO_TIMESTAMP
    }

    private fun initialize(measurements: FloatArray, timestampMs: Long) {
        positions = measurements.copyOf()
        lastMeasurements = measurements.copyOf()
        derivativeEstimates = FloatArray(measurements.size)
        velocities = FloatArray(measurements.size)
        filteredCentroidSpeed = 0f
        filteredCentroidVelocityX = 0f
        filteredCentroidVelocityY = 0f
        measuredCentroidVelocityX = 0f
        measuredCentroidVelocityY = 0f
        lastMeasurementTimestampMs = timestampMs
    }

    private fun calculateMeasuredCentroidVelocity(
        measurements: FloatArray,
        elapsedSeconds: Float,
    ) {
        var deltaX = 0f
        var deltaY = 0f
        var landmarkCount = 0
        var index = 0
        while (index < measurements.size) {
            deltaX += measurements[index] - lastMeasurements[index]
            deltaY += measurements[index + 1] - lastMeasurements[index + 1]
            landmarkCount++
            index += LandmarkRenderFrame.COORDINATE_COUNT
        }
        measuredCentroidVelocityX = deltaX / landmarkCount / elapsedSeconds
        measuredCentroidVelocityY = deltaY / landmarkCount / elapsedSeconds
    }

    private fun smoothingAlpha(cutoffHz: Float, elapsedSeconds: Float): Float {
        val timeConstant = 1f / (TWO_PI * cutoffHz)
        return 1f / (1f + timeConstant / elapsedSeconds)
    }

    private fun applyDeadZone(velocity: Float): Float {
        val magnitudeAfterDeadZone = (abs(velocity) - velocityDeadZone).coerceAtLeast(0f)
        return if (velocity < 0f) -magnitudeAfterDeadZone else magnitudeAfterDeadZone
    }

    private fun snapshot(predictedOnly: Boolean): LandmarkRenderFrame =
        LandmarkRenderFrame(
            positions = positions.copyOf(),
            velocities = velocities.copyOf(),
            measurementTimestampMs = lastMeasurementTimestampMs,
            predictedOnly = predictedOnly,
            renderLeadMs = renderLeadMs,
            maxPredictionMs = maxPredictionMs,
        )

    private fun hasImplausibleCentroidJump(
        measurements: FloatArray,
        elapsedMs: Long,
    ): Boolean {
        if (positions.size != measurements.size || elapsedMs <= 0L) return false

        val elapsedSeconds = elapsedMs / MILLIS_PER_SECOND
        var measuredX = 0f
        var measuredY = 0f
        var predictedX = 0f
        var predictedY = 0f
        var landmarkCount = 0
        var index = 0
        while (index < measurements.size) {
            measuredX += measurements[index]
            measuredY += measurements[index + 1]
            predictedX += positions[index] + velocities[index] * elapsedSeconds
            predictedY += positions[index + 1] + velocities[index + 1] * elapsedSeconds
            landmarkCount++
            index += LandmarkRenderFrame.COORDINATE_COUNT
        }
        measuredX /= landmarkCount
        measuredY /= landmarkCount
        predictedX /= landmarkCount
        predictedY /= landmarkCount
        val deltaX = measuredX - predictedX
        val deltaY = measuredY - predictedY
        return deltaX * deltaX + deltaY * deltaY > maxCentroidJump * maxCentroidJump
    }

    companion object {
        private const val DEFAULT_MIN_CUTOFF_HZ = 3f
        private const val DEFAULT_MAX_CUTOFF_HZ = 20f
        private const val DEFAULT_FACE_SPEED_COEFFICIENT = 15f
        private const val DEFAULT_LOCAL_SPEED_COEFFICIENT = 6f
        private const val DEFAULT_DERIVATIVE_CUTOFF_HZ = 6f
        private const val DEFAULT_GLOBAL_VELOCITY_CUTOFF_HZ = 20f
        private const val DEFAULT_GLOBAL_VELOCITY_DEAD_ZONE = 0.01f
        private const val DEFAULT_VELOCITY_DEAD_ZONE = 0.015f
        private const val DEFAULT_MAX_VELOCITY_PER_SECOND = 2f
        private const val DEFAULT_RENDER_LEAD_MS = 4L
        private const val DEFAULT_MAX_PREDICTION_MS = 45L
        private const val DEFAULT_HOLD_AFTER_LOSS_MS = 120L
        private const val DEFAULT_RESET_GAP_MS = 200L
        private const val DEFAULT_MAX_CENTROID_JUMP = 0.25f
        private const val STOP_RESPONSE_CUTOFF_HZ = 12f
        private const val X_OFFSET = 0
        private const val Y_OFFSET = 1
        private const val TWO_PI = 6.2831855f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
