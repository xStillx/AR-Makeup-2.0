package com.example.armakeup.tracking

import kotlin.math.sqrt

/**
 * Predicts only the rigid face pose from an ML camera frame to the camera frame being displayed.
 *
 * The current measurement remains the unfiltered base position. A robust similarity fit over
 * stable face anchors supplies one coherent screen-plane velocity for the whole mesh; lip-local
 * deformation is deliberately not extrapolated. This avoids both positional filter lag and the
 * unrelated per-landmark velocities that previously amplified stationary shimmer.
 */
internal class DisplayTimeLandmarkPredictor(
    private val maximumPredictionMs: Long = DEFAULT_MAXIMUM_PREDICTION_MS,
    private val maximumFrameGapMs: Long = DEFAULT_MAXIMUM_FRAME_GAP_MS,
    private val holdAfterLossMs: Long = DEFAULT_HOLD_AFTER_LOSS_MS,
    private val minimumMotionSpeed: Float = DEFAULT_MINIMUM_MOTION_SPEED,
    private val fullMotionSpeed: Float = DEFAULT_FULL_MOTION_SPEED,
    private val minimumUsefulQuality: Float = DEFAULT_MINIMUM_USEFUL_QUALITY,
    private val fullQuality: Float = DEFAULT_FULL_QUALITY,
    private val continuingVelocityResponse: Float = DEFAULT_CONTINUING_VELOCITY_RESPONSE,
    private val maximumVelocityPerSecond: Float = DEFAULT_MAXIMUM_VELOCITY_PER_SECOND,
) {
    private val similarityEstimator = RobustSimilarityEstimator()
    private var previousMeasurements = FloatArray(0)
    private var positions = FloatArray(0)
    private var velocities = FloatArray(0)
    private var instantaneousVelocities = FloatArray(0)
    private var lastMeasurementTimestampMs = NO_TIMESTAMP
    private var predictionCoverage = 0f

    var latestPoseFitQuality: TrackingPoseFitQuality = TrackingPoseFitQuality.UNKNOWN
        private set

    init {
        require(maximumPredictionMs >= 0L)
        require(maximumFrameGapMs > 0L)
        require(holdAfterLossMs >= maximumPredictionMs)
        require(minimumMotionSpeed >= 0f && fullMotionSpeed > minimumMotionSpeed)
        require(minimumUsefulQuality in 0f..1f && fullQuality > minimumUsefulQuality)
        require(fullQuality <= 1f)
        require(continuingVelocityResponse in 0f..1f)
        require(maximumVelocityPerSecond > 0f)
    }

    fun update(measurements: FloatArray, timestampMs: Long): LandmarkRenderFrame {
        require(
            measurements.isNotEmpty() &&
                measurements.size % LandmarkRenderFrame.COORDINATE_COUNT == 0,
        )

        val elapsedMs = timestampMs - lastMeasurementTimestampMs
        if (
            previousMeasurements.size != measurements.size ||
            lastMeasurementTimestampMs == NO_TIMESTAMP ||
            elapsedMs <= 0L ||
            elapsedMs > maximumFrameGapMs
        ) {
            initialize(measurements, timestampMs)
            return snapshot(predictedOnly = false)
        }

        val estimate = similarityEstimator.estimate(previousMeasurements, measurements)
        latestPoseFitQuality = if (estimate.isValid) {
            TrackingPoseFitQuality(
                normalizedRmsResidual = estimate.normalizedRmsResidual,
                inlierFraction = estimate.inlierFraction,
                quality = estimate.quality,
            )
        } else {
            TrackingPoseFitQuality.UNKNOWN
        }

        measurements.copyInto(positions)
        if (!estimate.isValid) {
            velocities.fill(0f)
            predictionCoverage = 0f
        } else {
            val elapsedSeconds = elapsedMs / MILLIS_PER_SECOND
            calculateInstantaneousRigidVelocities(estimate, elapsedSeconds)
            val speed = globalSpeed(instantaneousVelocities)
            val motionGain = smoothRange(speed, minimumMotionSpeed, fullMotionSpeed)
            val qualityGain = smoothRange(
                estimate.quality,
                minimumUsefulQuality,
                fullQuality,
            )
            predictionCoverage = motionGain * qualityGain
            updateVelocities(predictionCoverage)
        }

        measurements.copyInto(previousMeasurements)
        lastMeasurementTimestampMs = timestampMs
        return snapshot(predictedOnly = false)
    }

    fun predictWithoutMeasurement(timestampMs: Long): LandmarkRenderFrame? {
        if (lastMeasurementTimestampMs == NO_TIMESTAMP) return null
        if (timestampMs - lastMeasurementTimestampMs > holdAfterLossMs) {
            reset()
            return null
        }
        return snapshot(predictedOnly = true)
    }

    fun reset() {
        previousMeasurements = FloatArray(0)
        positions = FloatArray(0)
        velocities = FloatArray(0)
        instantaneousVelocities = FloatArray(0)
        lastMeasurementTimestampMs = NO_TIMESTAMP
        predictionCoverage = 0f
        latestPoseFitQuality = TrackingPoseFitQuality.UNKNOWN
    }

    private fun initialize(measurements: FloatArray, timestampMs: Long) {
        previousMeasurements = measurements.copyOf()
        positions = measurements.copyOf()
        velocities = FloatArray(measurements.size)
        instantaneousVelocities = FloatArray(measurements.size)
        lastMeasurementTimestampMs = timestampMs
        predictionCoverage = 0f
        latestPoseFitQuality = TrackingPoseFitQuality.UNKNOWN
    }

    private fun calculateInstantaneousRigidVelocities(
        estimate: RobustSimilarityEstimate,
        elapsedSeconds: Float,
    ) {
        var index = 0
        while (index < previousMeasurements.size) {
            val previousX = previousMeasurements[index]
            val previousY = previousMeasurements[index + 1]
            instantaneousVelocities[index] = (
                estimate.mapX(previousX, previousY) - previousX
            ) / elapsedSeconds
            instantaneousVelocities[index + 1] = (
                estimate.mapY(previousX, previousY) - previousY
            ) / elapsedSeconds
            // Depth and expression are not rigid screen-plane motion. Predicting them from two
            // noisy 30 Hz samples makes the lipstick edge breathe during a stationary hold.
            instantaneousVelocities[index + 2] = 0f
            index += LandmarkRenderFrame.COORDINATE_COUNT
        }
    }

    private fun updateVelocities(coverage: Float) {
        if (coverage <= 0f) {
            velocities.fill(0f)
            return
        }
        val directionCosine = directionCosine(instantaneousVelocities, velocities)
        val response = if (
            directionCosine.isNaN() || directionCosine < REVERSAL_DIRECTION_COSINE
        ) {
            1f
        } else {
            continuingVelocityResponse
        }
        velocities.indices.forEach { index ->
            val target = instantaneousVelocities[index] * coverage
            velocities[index] = (
                velocities[index] + response * (target - velocities[index])
            ).coerceIn(-maximumVelocityPerSecond, maximumVelocityPerSecond)
        }
    }

    private fun globalSpeed(values: FloatArray): Float {
        var squaredSpeed = 0f
        var count = 0
        TrackingGeometryExtractor.stableAnchorIndices.forEach { landmarkIndex ->
            val index = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            if (index + 1 >= values.size) return@forEach
            squaredSpeed += values[index] * values[index] + values[index + 1] * values[index + 1]
            count++
        }
        return if (count == 0) 0f else sqrt(squaredSpeed / count)
    }

    private fun directionCosine(first: FloatArray, second: FloatArray): Float {
        var dot = 0f
        var firstSquared = 0f
        var secondSquared = 0f
        TrackingGeometryExtractor.stableAnchorIndices.forEach { landmarkIndex ->
            val index = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            if (index + 1 >= first.size || index + 1 >= second.size) return@forEach
            dot += first[index] * second[index] + first[index + 1] * second[index + 1]
            firstSquared += first[index] * first[index] + first[index + 1] * first[index + 1]
            secondSquared += second[index] * second[index] + second[index + 1] * second[index + 1]
        }
        val denominator = sqrt(firstSquared * secondSquared)
        return if (denominator <= MINIMUM_DIRECTION_MAGNITUDE) Float.NaN else dot / denominator
    }

    private fun snapshot(predictedOnly: Boolean): LandmarkRenderFrame = LandmarkRenderFrame(
        positions = positions.copyOf(),
        velocities = velocities.copyOf(),
        measurementTimestampMs = lastMeasurementTimestampMs,
        predictedOnly = predictedOnly,
        renderLeadMs = 0L,
        maxPredictionMs = maximumPredictionMs,
        maxRenderExtrapolationMs = 0L,
        globalPredictionCoverage = predictionCoverage,
    )

    private fun smoothRange(value: Float, start: Float, end: Float): Float {
        val linear = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return linear * linear * (3f - 2f * linear)
    }

    companion object {
        private const val DEFAULT_MAXIMUM_PREDICTION_MS = 50L
        private const val DEFAULT_MAXIMUM_FRAME_GAP_MS = 100L
        private const val DEFAULT_HOLD_AFTER_LOSS_MS = 100L
        private const val DEFAULT_MINIMUM_MOTION_SPEED = 0.025f
        private const val DEFAULT_FULL_MOTION_SPEED = 0.12f
        private const val DEFAULT_MINIMUM_USEFUL_QUALITY = 0.25f
        private const val DEFAULT_FULL_QUALITY = 0.70f
        private const val DEFAULT_CONTINUING_VELOCITY_RESPONSE = 0.72f
        private const val DEFAULT_MAXIMUM_VELOCITY_PER_SECOND = 2.5f
        private const val REVERSAL_DIRECTION_COSINE = 0.25f
        private const val MINIMUM_DIRECTION_MAGNITUDE = 1e-8f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
