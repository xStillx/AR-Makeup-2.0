package com.example.armakeup.tracking

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Stateful global-pose/local-deformation filter with bounded render prediction.
 *
 * Global motion is estimated by a robust similarity fit over rigid eye/nose/forehead/cheek
 * anchors. This prevents one unstable landmark, device rotation or scale changes from becoming
 * unrelated per-landmark velocity.
 * Full extrapolation is enabled only after coherent motion persists across several ML results;
 * a stop or real reversal clears predictive velocity, while one noisy direction sample only
 * reduces confidence smoothly.
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
    private val motionStartSpeed: Float = DEFAULT_MOTION_START_SPEED,
    private val motionStopSpeed: Float = DEFAULT_MOTION_STOP_SPEED,
    private val motionConfirmationFrames: Int = DEFAULT_MOTION_CONFIRMATION_FRAMES,
    private val candidatePredictionGain: Float = DEFAULT_CANDIDATE_PREDICTION_GAIN,
    private val localPredictionGain: Float = DEFAULT_LOCAL_PREDICTION_GAIN,
    private val renderLeadMs: Long = DEFAULT_RENDER_LEAD_MS,
    private val maxPredictionMs: Long = DEFAULT_MAX_PREDICTION_MS,
    private val maxCoherentMotionPredictionMs: Long =
        DEFAULT_MAX_COHERENT_MOTION_PREDICTION_MS,
    private val holdAfterLossMs: Long = DEFAULT_HOLD_AFTER_LOSS_MS,
    private val resetGapMs: Long = DEFAULT_RESET_GAP_MS,
    private val maxCentroidJump: Float = DEFAULT_MAX_CENTROID_JUMP,
) {
    private val similarityEstimator = RobustSimilarityEstimator()
    private var positions = FloatArray(0)
    private var lastMeasurements = FloatArray(0)
    private var localDerivativeEstimates = FloatArray(0)
    private var measuredGlobalVelocities = FloatArray(0)
    private var previousMeasuredGlobalVelocities = FloatArray(0)
    private var filteredGlobalVelocities = FloatArray(0)
    private var velocities = FloatArray(0)
    private var filteredGlobalSpeed = 0f
    private var previousGlobalSpeed = 0f
    private var globalPredictionGain = 0f
    private var currentPredictionHorizonMs = maxPredictionMs
    private var filteredPoseFitQuality = 1f
    private var motionState = MotionState.STATIONARY
    private var coherentMotionFrames = 0
    private var measurementMissingSinceLastUpdate = false
    private var lastMeasurementTimestampMs = NO_TIMESTAMP

    var latestPoseFitQuality: TrackingPoseFitQuality = TrackingPoseFitQuality.UNKNOWN
        private set

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
        require(motionStartSpeed > motionStopSpeed)
        require(motionStopSpeed >= 0f)
        require(motionConfirmationFrames >= 2)
        require(candidatePredictionGain in 0f..1f)
        require(localPredictionGain in 0f..1f)
        require(renderLeadMs >= 0L)
        require(maxPredictionMs >= 0L)
        require(maxCoherentMotionPredictionMs >= maxPredictionMs)
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
            measurementMissingSinceLastUpdate ||
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
        calculateMeasuredGlobalVelocities(measurements, elapsedSeconds)
        updateFilteredPoseFitQuality(elapsedSeconds)
        val qualityResponse = MINIMUM_QUALITY_RESPONSE +
            (1f - MINIMUM_QUALITY_RESPONSE) * filteredPoseFitQuality
        val measuredGlobalSpeed = globalMotionSpeed(measuredGlobalVelocities)
        val directionCosine = globalDirectionCosine(
            measuredGlobalVelocities,
            previousMeasuredGlobalVelocities,
        )
        val stateTransition = updateMotionState(measuredGlobalSpeed, directionCosine)
        updateGlobalPredictionGain(elapsedSeconds, stateTransition)
        updatePredictionHorizon(measuredGlobalSpeed)
        val positionMotionGain = if (motionState == MotionState.STATIONARY) {
            0f
        } else {
            CANDIDATE_POSITION_RESPONSE_GAIN +
                (1f - CANDIDATE_POSITION_RESPONSE_GAIN) * globalPredictionGain
        }
        val effectiveGlobalSpeed = (
            measuredGlobalSpeed - globalVelocityDeadZone
        ).coerceAtLeast(0f) * positionMotionGain * qualityResponse
        filteredGlobalSpeed = when {
            motionState == MotionState.STATIONARY -> 0f
            effectiveGlobalSpeed > filteredGlobalSpeed -> effectiveGlobalSpeed
            else -> filteredGlobalSpeed + derivativeAlpha * (
                effectiveGlobalSpeed - filteredGlobalSpeed
            )
        }
        val faceCutoffHz = (
            minCutoffHz + faceSpeedCoefficient * filteredGlobalSpeed
        ).coerceAtMost(maxCutoffHz)
        val qualityLimitedMaxCutoffHz = minCutoffHz +
            (maxCutoffHz - minCutoffHz) * qualityResponse
        val globalVelocityAlpha = smoothingAlpha(
            globalVelocityCutoffHz,
            elapsedSeconds,
        ) * qualityResponse

        for (index in measurements.indices) {
            val rawDerivative = (measurements[index] - lastMeasurements[index]) / elapsedSeconds
            val rawLocalDerivative = rawDerivative - measuredGlobalVelocities[index]
            val localDerivativeAlpha = if (
                rawLocalDerivative * localDerivativeEstimates[index] <= 0f
            ) {
                stopAlpha
            } else {
                derivativeAlpha
            }
            localDerivativeEstimates[index] += localDerivativeAlpha * (
                rawLocalDerivative - localDerivativeEstimates[index]
            )
            val localCutoffHz = (
                minCutoffHz + localSpeedCoefficient * abs(localDerivativeEstimates[index])
            ).coerceAtMost(maxCutoffHz)
            val positionAlpha = smoothingAlpha(
                cutoffHz = max(faceCutoffHz, localCutoffHz)
                    .coerceAtMost(qualityLimitedMaxCutoffHz),
                elapsedSeconds = elapsedSeconds,
            )
            positions[index] += positionAlpha * (measurements[index] - positions[index])

            filteredGlobalVelocities[index] = when {
                motionState == MotionState.STATIONARY -> 0f
                stateTransition.resetsVelocity -> measuredGlobalVelocities[index]
                else -> filteredGlobalVelocities[index] + globalVelocityAlpha * (
                    measuredGlobalVelocities[index] - filteredGlobalVelocities[index]
                )
            }
            val predictiveLocalVelocity = if (motionState == MotionState.MOVING) {
                applyDeadZone(localDerivativeEstimates[index]) *
                    localPredictionGain * qualityResponse
            } else {
                0f
            }
            velocities[index] = (
                filteredGlobalVelocities[index] * globalPredictionGain * qualityResponse +
                    predictiveLocalVelocity
            ).coerceIn(-maxVelocityPerSecond, maxVelocityPerSecond)
            lastMeasurements[index] = measurements[index]
        }
        measuredGlobalVelocities.copyInto(previousMeasuredGlobalVelocities)
        previousGlobalSpeed = measuredGlobalSpeed
        lastMeasurementTimestampMs = timestampMs
        return snapshot(predictedOnly = false)
    }

    fun predictWithoutMeasurement(nowMs: Long): LandmarkRenderFrame? {
        if (lastMeasurementTimestampMs == NO_TIMESTAMP) return null
        if (nowMs - lastMeasurementTimestampMs > holdAfterLossMs) {
            reset()
            return null
        }
        measurementMissingSinceLastUpdate = true
        return snapshot(predictedOnly = true)
    }

    fun reset() {
        positions = FloatArray(0)
        lastMeasurements = FloatArray(0)
        localDerivativeEstimates = FloatArray(0)
        measuredGlobalVelocities = FloatArray(0)
        previousMeasuredGlobalVelocities = FloatArray(0)
        filteredGlobalVelocities = FloatArray(0)
        velocities = FloatArray(0)
        filteredGlobalSpeed = 0f
        previousGlobalSpeed = 0f
        globalPredictionGain = 0f
        currentPredictionHorizonMs = maxPredictionMs
        filteredPoseFitQuality = 1f
        motionState = MotionState.STATIONARY
        coherentMotionFrames = 0
        measurementMissingSinceLastUpdate = false
        lastMeasurementTimestampMs = NO_TIMESTAMP
        latestPoseFitQuality = TrackingPoseFitQuality.UNKNOWN
    }

    private fun initialize(measurements: FloatArray, timestampMs: Long) {
        positions = measurements.copyOf()
        lastMeasurements = measurements.copyOf()
        localDerivativeEstimates = FloatArray(measurements.size)
        measuredGlobalVelocities = FloatArray(measurements.size)
        previousMeasuredGlobalVelocities = FloatArray(measurements.size)
        filteredGlobalVelocities = FloatArray(measurements.size)
        velocities = FloatArray(measurements.size)
        filteredGlobalSpeed = 0f
        previousGlobalSpeed = 0f
        globalPredictionGain = 0f
        currentPredictionHorizonMs = maxPredictionMs
        filteredPoseFitQuality = 1f
        motionState = MotionState.STATIONARY
        coherentMotionFrames = 0
        measurementMissingSinceLastUpdate = false
        lastMeasurementTimestampMs = timestampMs
        latestPoseFitQuality = TrackingPoseFitQuality.UNKNOWN
    }

    private fun calculateMeasuredGlobalVelocities(
        measurements: FloatArray,
        elapsedSeconds: Float,
    ) {
        val estimate = similarityEstimator.estimate(lastMeasurements, measurements)
        if (estimate.isValid) {
            latestPoseFitQuality = TrackingPoseFitQuality(
                normalizedRmsResidual = estimate.normalizedRmsResidual,
                inlierFraction = estimate.inlierFraction,
                quality = estimate.quality,
            )
            var index = 0
            while (index < measurements.size) {
                val mappedX = estimate.mapX(lastMeasurements[index], lastMeasurements[index + 1])
                val mappedY = estimate.mapY(lastMeasurements[index], lastMeasurements[index + 1])
                measuredGlobalVelocities[index] =
                    (mappedX - lastMeasurements[index]) / elapsedSeconds
                measuredGlobalVelocities[index + 1] =
                    (mappedY - lastMeasurements[index + 1]) / elapsedSeconds
                measuredGlobalVelocities[index + 2] = 0f
                index += LandmarkRenderFrame.COORDINATE_COUNT
            }
            return
        }

        latestPoseFitQuality = TrackingPoseFitQuality.UNKNOWN

        var velocityX = 0f
        var velocityY = 0f
        val landmarkCount = measurements.size / LandmarkRenderFrame.COORDINATE_COUNT
        var index = 0
        while (index < measurements.size) {
            velocityX += measurements[index] - lastMeasurements[index]
            velocityY += measurements[index + 1] - lastMeasurements[index + 1]
            index += LandmarkRenderFrame.COORDINATE_COUNT
        }
        velocityX /= landmarkCount * elapsedSeconds
        velocityY /= landmarkCount * elapsedSeconds
        index = 0
        while (index < measurements.size) {
            measuredGlobalVelocities[index] = velocityX
            measuredGlobalVelocities[index + 1] = velocityY
            measuredGlobalVelocities[index + 2] = 0f
            index += LandmarkRenderFrame.COORDINATE_COUNT
        }
    }

    private fun updateFilteredPoseFitQuality(elapsedSeconds: Float) {
        val target = latestPoseFitQuality.quality.takeIf { it.isFinite() } ?: 1f
        val ratePerSecond = if (target < filteredPoseFitQuality) {
            QUALITY_FALL_PER_SECOND
        } else {
            QUALITY_RISE_PER_SECOND
        }
        val maximumDelta = ratePerSecond * elapsedSeconds
        filteredPoseFitQuality += (target - filteredPoseFitQuality).coerceIn(
            -maximumDelta,
            maximumDelta,
        )
        filteredPoseFitQuality = filteredPoseFitQuality.coerceIn(0f, 1f)
    }

    private fun updateMotionState(speed: Float, directionCosine: Float): StateTransition {
        val previousState = motionState
        val comparableMotion = previousGlobalSpeed >= motionStartSpeed &&
            speed >= motionStartSpeed
        val directionCoherent = !comparableMotion ||
            directionCosine >= MIN_COHERENT_DIRECTION_COSINE
        val hardDirectionReversal = comparableMotion &&
            directionCosine <= HARD_DIRECTION_REVERSAL_COSINE

        when (motionState) {
            MotionState.STATIONARY -> if (speed >= motionStartSpeed) {
                motionState = MotionState.CANDIDATE
                coherentMotionFrames = 1
            }
            MotionState.CANDIDATE -> when {
                speed <= motionStopSpeed -> {
                    motionState = MotionState.STATIONARY
                    coherentMotionFrames = 0
                }
                hardDirectionReversal -> coherentMotionFrames = 1
                speed >= motionStartSpeed && directionCoherent -> {
                    coherentMotionFrames++
                    if (coherentMotionFrames >= motionConfirmationFrames) {
                        motionState = MotionState.MOVING
                    }
                }
                speed >= motionStartSpeed -> {
                    coherentMotionFrames = (coherentMotionFrames - 1).coerceAtLeast(1)
                }
            }
            MotionState.MOVING -> when {
                speed <= motionStopSpeed -> {
                    motionState = MotionState.STATIONARY
                    coherentMotionFrames = 0
                }
                hardDirectionReversal -> {
                    motionState = MotionState.CANDIDATE
                    coherentMotionFrames = 1
                }
            }
        }
        val startedMotion = previousState == MotionState.STATIONARY &&
            motionState == MotionState.CANDIDATE
        return StateTransition(
            resetsVelocity = motionState == MotionState.STATIONARY ||
                hardDirectionReversal ||
                startedMotion,
            startedMotion = startedMotion,
            hardDirectionReversal = hardDirectionReversal,
            directionCoherent = directionCoherent,
        )
    }

    private fun updateGlobalPredictionGain(
        elapsedSeconds: Float,
        transition: StateTransition,
    ) {
        globalPredictionGain = when {
            motionState == MotionState.STATIONARY -> 0f
            transition.startedMotion || transition.hardDirectionReversal -> {
                candidatePredictionGain
            }
            transition.directionCoherent -> (
                globalPredictionGain + PREDICTION_GAIN_ATTACK_PER_SECOND * elapsedSeconds
            ).coerceIn(candidatePredictionGain, 1f)
            else -> (
                globalPredictionGain - PREDICTION_GAIN_RELEASE_PER_SECOND * elapsedSeconds
            ).coerceIn(candidatePredictionGain, 1f)
        }
    }

    private fun updatePredictionHorizon(measuredGlobalSpeed: Float) {
        if (motionState == MotionState.STATIONARY) {
            currentPredictionHorizonMs = maxPredictionMs
            return
        }
        val instantQuality = latestPoseFitQuality.quality
        if (!instantQuality.isFinite()) {
            currentPredictionHorizonMs = maxPredictionMs
            return
        }
        val quality = min(filteredPoseFitQuality, instantQuality)
        val qualityFactor = normalizedRange(
            quality,
            MIN_FAST_MOTION_POSE_QUALITY,
            FULL_FAST_MOTION_POSE_QUALITY,
        )
        val speedFactor = normalizedRange(
            measuredGlobalSpeed,
            FAST_MOTION_START_SPEED,
            FAST_MOTION_FULL_SPEED,
        )
        val confidenceFactor = normalizedRange(
            globalPredictionGain,
            candidatePredictionGain,
            1f,
        )
        val additionalHorizonMs = (
            (maxCoherentMotionPredictionMs - maxPredictionMs) *
                qualityFactor * speedFactor * confidenceFactor
        ).roundToLong()
        currentPredictionHorizonMs = maxPredictionMs + additionalHorizonMs
    }

    private fun normalizedRange(value: Float, start: Float, end: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        return ((value - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun globalMotionSpeed(globalVelocities: FloatArray): Float {
        var squaredSpeed = 0f
        var count = 0
        forEachMotionAnchor(globalVelocities) { coordinateIndex ->
            val x = globalVelocities[coordinateIndex]
            val y = globalVelocities[coordinateIndex + 1]
            squaredSpeed += x * x + y * y
            count++
        }
        return if (count == 0) 0f else sqrt(squaredSpeed / count)
    }

    private fun globalDirectionCosine(current: FloatArray, previous: FloatArray): Float {
        var dot = 0f
        var currentSquared = 0f
        var previousSquared = 0f
        forEachMotionAnchor(current) { coordinateIndex ->
            val currentX = current[coordinateIndex]
            val currentY = current[coordinateIndex + 1]
            val previousX = previous[coordinateIndex]
            val previousY = previous[coordinateIndex + 1]
            dot += currentX * previousX + currentY * previousY
            currentSquared += currentX * currentX + currentY * currentY
            previousSquared += previousX * previousX + previousY * previousY
        }
        val denominator = sqrt(currentSquared * previousSquared)
        return if (denominator <= MIN_DIRECTION_NORM) 1f else dot / denominator
    }

    private inline fun forEachMotionAnchor(
        coordinates: FloatArray,
        action: (coordinateIndex: Int) -> Unit,
    ) {
        val landmarkCount = coordinates.size / LandmarkRenderFrame.COORDINATE_COUNT
        val stableAnchors = TrackingGeometryExtractor.stableAnchorIndices
        if (landmarkCount > (stableAnchors.maxOrNull() ?: Int.MAX_VALUE)) {
            stableAnchors.forEach { action(it * LandmarkRenderFrame.COORDINATE_COUNT) }
        } else {
            var coordinateIndex = 0
            while (coordinateIndex < coordinates.size) {
                action(coordinateIndex)
                coordinateIndex += LandmarkRenderFrame.COORDINATE_COUNT
            }
        }
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
            maxPredictionMs = currentPredictionHorizonMs,
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
        var count = 0
        forEachMotionAnchor(measurements) { coordinateIndex ->
            measuredX += measurements[coordinateIndex]
            measuredY += measurements[coordinateIndex + 1]
            predictedX += positions[coordinateIndex] + velocities[coordinateIndex] * elapsedSeconds
            predictedY += positions[coordinateIndex + 1] +
                velocities[coordinateIndex + 1] * elapsedSeconds
            count++
        }
        if (count == 0) return false
        measuredX /= count
        measuredY /= count
        predictedX /= count
        predictedY /= count
        val deltaX = measuredX - predictedX
        val deltaY = measuredY - predictedY
        return deltaX * deltaX + deltaY * deltaY > maxCentroidJump * maxCentroidJump
    }

    private enum class MotionState {
        STATIONARY,
        CANDIDATE,
        MOVING,
    }

    private data class StateTransition(
        val resetsVelocity: Boolean,
        val startedMotion: Boolean,
        val hardDirectionReversal: Boolean,
        val directionCoherent: Boolean,
    )

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
        private const val DEFAULT_MOTION_START_SPEED = 0.08f
        private const val DEFAULT_MOTION_STOP_SPEED = 0.035f
        private const val DEFAULT_MOTION_CONFIRMATION_FRAMES = 3
        private const val DEFAULT_CANDIDATE_PREDICTION_GAIN = 0.2f
        private const val DEFAULT_LOCAL_PREDICTION_GAIN = 0.35f
        private const val DEFAULT_RENDER_LEAD_MS = 4L
        private const val DEFAULT_MAX_PREDICTION_MS = 45L
        private const val DEFAULT_MAX_COHERENT_MOTION_PREDICTION_MS = 85L
        private const val DEFAULT_HOLD_AFTER_LOSS_MS = 120L
        private const val DEFAULT_RESET_GAP_MS = 200L
        private const val DEFAULT_MAX_CENTROID_JUMP = 0.12f
        private const val CANDIDATE_POSITION_RESPONSE_GAIN = 0.4f
        private const val MIN_COHERENT_DIRECTION_COSINE = 0.25f
        private const val HARD_DIRECTION_REVERSAL_COSINE = -0.25f
        private const val PREDICTION_GAIN_ATTACK_PER_SECOND = 8f
        private const val PREDICTION_GAIN_RELEASE_PER_SECOND = 4f
        private const val MINIMUM_QUALITY_RESPONSE = 0.35f
        private const val MIN_FAST_MOTION_POSE_QUALITY = 0.55f
        private const val FULL_FAST_MOTION_POSE_QUALITY = 0.75f
        private const val FAST_MOTION_START_SPEED = 0.25f
        private const val FAST_MOTION_FULL_SPEED = 0.65f
        private const val QUALITY_FALL_PER_SECOND = 8f
        private const val QUALITY_RISE_PER_SECOND = 3f
        private const val MIN_DIRECTION_NORM = 1e-8f
        private const val STOP_RESPONSE_CUTOFF_HZ = 12f
        private const val TWO_PI = 6.2831855f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
