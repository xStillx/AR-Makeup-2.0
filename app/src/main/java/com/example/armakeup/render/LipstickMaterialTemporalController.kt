package com.example.armakeup.render

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Keeps camera-conditioned lipstick detail temporally coherent with the displayed lip mesh.
 *
 * The controller never changes landmarks or geometry. It estimates how far camera-derived detail
 * can be displaced from the predicted mesh, then smoothly reduces only that detail while the
 * mismatch is large. Coordinates are measured in fractions of the viewport's short edge so the
 * thresholds remain comparable in portrait and landscape.
 */
internal class LipstickMaterialTemporalController {
    private var previousOuter = FloatArray(0)
    private var previousInner = FloatArray(0)
    private var previousTimestampMs = NO_TIMESTAMP
    private var filteredMotionSpeed = 0f
    private var cameraDetailCoherence = FULL_COHERENCE

    fun update(
        timestampMs: Long,
        outerPoints: FloatArray,
        innerPoints: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        temporalMismatchMs: Float,
    ): State {
        if (
            !validPoints(outerPoints) ||
            !validPoints(innerPoints) ||
            viewportWidth <= 0 ||
            viewportHeight <= 0
        ) {
            reset()
            return currentState(temporalMismatchMs)
        }

        val elapsedMs = timestampMs - previousTimestampMs
        if (
            previousTimestampMs == NO_TIMESTAMP ||
            elapsedMs <= 0L ||
            elapsedMs > MAX_CONTINUOUS_INTERVAL_MS ||
            previousOuter.size != outerPoints.size ||
            previousInner.size != innerPoints.size
        ) {
            reset()
            copyPoints(outerPoints, innerPoints)
            previousTimestampMs = timestampMs
            return currentState(temporalMismatchMs)
        }

        val elapsedSeconds = elapsedMs / MILLIS_PER_SECOND
        val instantaneousSpeed = pointCloudSpeed(
            outerPoints = outerPoints,
            innerPoints = innerPoints,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            elapsedSeconds = elapsedSeconds,
        )
        val speedRate = if (instantaneousSpeed > filteredMotionSpeed) {
            MOTION_ATTACK_RATE
        } else {
            MOTION_RELEASE_RATE
        }
        filteredMotionSpeed += smoothingAlpha(speedRate, elapsedSeconds) *
            (instantaneousSpeed - filteredMotionSpeed)

        val mismatchSeconds = temporalMismatchMs
            .takeIf { it.isFinite() && it >= 0f }
            ?.div(MILLIS_PER_SECOND)
            ?: FALLBACK_TEMPORAL_MISMATCH_SECONDS
        val estimatedSpatialMismatch = filteredMotionSpeed * mismatchSeconds
        val mismatchProgress = smoothstep(
            MISMATCH_START_SHORT_EDGE_FRACTION,
            MISMATCH_FULL_SHORT_EDGE_FRACTION,
            estimatedSpatialMismatch,
        )
        val targetCoherence = lerp(
            FULL_COHERENCE,
            MINIMUM_CAMERA_DETAIL_COHERENCE,
            mismatchProgress,
        )
        val coherenceRate = if (targetCoherence < cameraDetailCoherence) {
            COHERENCE_ATTACK_RATE
        } else {
            COHERENCE_RELEASE_RATE
        }
        cameraDetailCoherence += smoothingAlpha(coherenceRate, elapsedSeconds) *
            (targetCoherence - cameraDetailCoherence)
        cameraDetailCoherence = cameraDetailCoherence.coerceIn(
            MINIMUM_CAMERA_DETAIL_COHERENCE,
            FULL_COHERENCE,
        )

        copyPoints(outerPoints, innerPoints)
        previousTimestampMs = timestampMs
        return State(
            motionSpeedShortEdgesPerSecond = filteredMotionSpeed,
            temporalMismatchMs = temporalMismatchMs,
            estimatedSpatialMismatch = estimatedSpatialMismatch,
            cameraDetailCoherence = cameraDetailCoherence,
        )
    }

    fun reset() {
        previousOuter = FloatArray(0)
        previousInner = FloatArray(0)
        previousTimestampMs = NO_TIMESTAMP
        filteredMotionSpeed = 0f
        cameraDetailCoherence = FULL_COHERENCE
    }

    private fun currentState(temporalMismatchMs: Float) = State(
        motionSpeedShortEdgesPerSecond = filteredMotionSpeed,
        temporalMismatchMs = temporalMismatchMs,
        estimatedSpatialMismatch = 0f,
        cameraDetailCoherence = cameraDetailCoherence,
    )

    private fun pointCloudSpeed(
        outerPoints: FloatArray,
        innerPoints: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        elapsedSeconds: Float,
    ): Float {
        val shortEdge = minOf(viewportWidth, viewportHeight).toFloat()
        val horizontalScale = viewportWidth / shortEdge
        val verticalScale = viewportHeight / shortEdge
        var squaredDistanceSum = 0f
        var pointCount = 0

        fun accumulate(current: FloatArray, previous: FloatArray) {
            var index = 0
            while (index < current.size) {
                val deltaX = (current[index] - previous[index]) * horizontalScale
                val deltaY = (current[index + 1] - previous[index + 1]) * verticalScale
                squaredDistanceSum += deltaX * deltaX + deltaY * deltaY
                pointCount++
                index += POINT_COMPONENT_COUNT
            }
        }

        accumulate(outerPoints, previousOuter)
        accumulate(innerPoints, previousInner)
        if (pointCount == 0) return 0f
        return (sqrt(squaredDistanceSum / pointCount) / elapsedSeconds)
            .coerceIn(0f, MAXIMUM_MOTION_SPEED)
    }

    private fun copyPoints(outerPoints: FloatArray, innerPoints: FloatArray) {
        if (previousOuter.size != outerPoints.size) previousOuter = FloatArray(outerPoints.size)
        if (previousInner.size != innerPoints.size) previousInner = FloatArray(innerPoints.size)
        outerPoints.copyInto(previousOuter)
        innerPoints.copyInto(previousInner)
    }

    private fun validPoints(points: FloatArray): Boolean =
        points.isNotEmpty() && points.size % POINT_COMPONENT_COUNT == 0 && points.all(Float::isFinite)

    private fun smoothingAlpha(ratePerSecond: Float, elapsedSeconds: Float): Float =
        (1f - exp(-ratePerSecond * elapsedSeconds)).coerceIn(0f, 1f)

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val progress = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return progress * progress * (3f - 2f * progress)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    data class State(
        val motionSpeedShortEdgesPerSecond: Float,
        val temporalMismatchMs: Float,
        val estimatedSpatialMismatch: Float,
        val cameraDetailCoherence: Float,
    ) {
        companion object {
            val STATIONARY = State(
                motionSpeedShortEdgesPerSecond = 0f,
                temporalMismatchMs = Float.NaN,
                estimatedSpatialMismatch = 0f,
                cameraDetailCoherence = FULL_COHERENCE,
            )
        }
    }

    private companion object {
        private const val POINT_COMPONENT_COUNT = 2
        private const val NO_TIMESTAMP = Long.MIN_VALUE
        private const val MILLIS_PER_SECOND = 1_000f
        private const val MAX_CONTINUOUS_INTERVAL_MS = 250L
        private const val MAXIMUM_MOTION_SPEED = 3f
        private const val MOTION_ATTACK_RATE = 18f
        private const val MOTION_RELEASE_RATE = 6f
        private const val COHERENCE_ATTACK_RATE = 16f
        private const val COHERENCE_RELEASE_RATE = 3.5f
        private const val FULL_COHERENCE = 1f
        private const val MINIMUM_CAMERA_DETAIL_COHERENCE = 0.18f
        private const val MISMATCH_START_SHORT_EDGE_FRACTION = 0.003f
        private const val MISMATCH_FULL_SHORT_EDGE_FRACTION = 0.018f
        private const val FALLBACK_TEMPORAL_MISMATCH_SECONDS = 0.02f
    }
}
