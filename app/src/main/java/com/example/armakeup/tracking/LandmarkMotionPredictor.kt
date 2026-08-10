package com.example.armakeup.tracking

/**
 * A bounded alpha-beta tracker for normalized face landmarks.
 *
 * It estimates velocity from consecutive measurements and lets the renderer extrapolate to its
 * own timestamp. Prediction is deliberately short and capped so a lost face cannot keep flying
 * across the screen.
 */
class LandmarkMotionPredictor(
    private val positionGain: Float = DEFAULT_POSITION_GAIN,
    private val velocityGain: Float = DEFAULT_VELOCITY_GAIN,
    private val maxVelocityPerSecond: Float = DEFAULT_MAX_VELOCITY_PER_SECOND,
    private val renderLeadMs: Long = DEFAULT_RENDER_LEAD_MS,
    private val maxPredictionMs: Long = DEFAULT_MAX_PREDICTION_MS,
    private val holdAfterLossMs: Long = DEFAULT_HOLD_AFTER_LOSS_MS,
    private val resetGapMs: Long = DEFAULT_RESET_GAP_MS,
    private val maxCentroidJump: Float = DEFAULT_MAX_CENTROID_JUMP,
) {
    private var positions = FloatArray(0)
    private var velocities = FloatArray(0)
    private var lastMeasurementTimestampMs = NO_TIMESTAMP

    init {
        require(positionGain in 0f..1f)
        require(velocityGain in 0f..1f)
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
        for (index in measurements.indices) {
            val predictedPosition = positions[index] + velocities[index] * elapsedSeconds
            val residual = measurements[index] - predictedPosition
            positions[index] = predictedPosition + positionGain * residual
            velocities[index] = (
                velocities[index] + velocityGain * residual / elapsedSeconds
                ).coerceIn(-maxVelocityPerSecond, maxVelocityPerSecond)
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
        velocities = FloatArray(0)
        lastMeasurementTimestampMs = NO_TIMESTAMP
    }

    private fun initialize(measurements: FloatArray, timestampMs: Long) {
        positions = measurements.copyOf()
        velocities = FloatArray(measurements.size)
        lastMeasurementTimestampMs = timestampMs
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
        private const val DEFAULT_POSITION_GAIN = 0.85f
        private const val DEFAULT_VELOCITY_GAIN = 0.35f
        private const val DEFAULT_MAX_VELOCITY_PER_SECOND = 3f
        private const val DEFAULT_RENDER_LEAD_MS = 8L
        private const val DEFAULT_MAX_PREDICTION_MS = 65L
        private const val DEFAULT_HOLD_AFTER_LOSS_MS = 120L
        private const val DEFAULT_RESET_GAP_MS = 200L
        private const val DEFAULT_MAX_CENTROID_JUMP = 0.25f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
