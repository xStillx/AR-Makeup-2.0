package com.example.armakeup.tracking

/** Immutable landmark state that can be extrapolated at the actual render timestamp. */
class LandmarkRenderFrame internal constructor(
    private val positions: FloatArray,
    private val velocities: FloatArray,
    val measurementTimestampMs: Long,
    val predictedOnly: Boolean,
    private val renderLeadMs: Long,
    private val maxPredictionMs: Long,
    private val renderDeliveryTimestampMs: Long? = null,
    private val maxRenderExtrapolationMs: Long = DEFAULT_MAX_RENDER_EXTRAPOLATION_MS,
    private val renderCorrectionOffsets: FloatArray? = null,
) {
    init {
        require(positions.isNotEmpty() && positions.size % COORDINATE_COUNT == 0)
        require(positions.size == velocities.size)
        require(renderCorrectionOffsets == null || renderCorrectionOffsets.size == positions.size)
        require(maxRenderExtrapolationMs >= 0L)
    }

    val size: Int = positions.size / COORDINATE_COUNT

    /** Debug/replay snapshot before render extrapolation and visual continuity correction. */
    internal fun copyBasePositions(): FloatArray = positions.copyOf()

    /** Debug/replay snapshot of the bounded prediction velocity owned by the tracker. */
    internal fun copyVelocities(): FloatArray = velocities.copyOf()

    fun predictionSeconds(renderTimestampMs: Long): Float {
        val deliveryTimestampMs = renderDeliveryTimestampMs
        val predictionMs = if (deliveryTimestampMs == null) {
            (renderTimestampMs - measurementTimestampMs + renderLeadMs)
                .coerceIn(0L, maxPredictionMs)
        } else {
            val alignmentPredictionMs = (
                deliveryTimestampMs - measurementTimestampMs + renderLeadMs
            ).coerceIn(0L, maxPredictionMs)
            val renderExtrapolationMs = (renderTimestampMs - deliveryTimestampMs)
                .coerceIn(0L, maxRenderExtrapolationMs)
            alignmentPredictionMs + renderExtrapolationMs
        }
        return predictionMs / MILLIS_PER_SECOND
    }

    fun x(index: Int, predictionSeconds: Float): Float =
        predictedCoordinate(index, X_OFFSET, predictionSeconds)
            .coerceIn(-DISPLAY_MARGIN, 1f + DISPLAY_MARGIN)

    fun y(index: Int, predictionSeconds: Float): Float =
        predictedCoordinate(index, Y_OFFSET, predictionSeconds)
            .coerceIn(-DISPLAY_MARGIN, 1f + DISPLAY_MARGIN)

    fun z(index: Int, predictionSeconds: Float): Float =
        predictedCoordinate(index, Z_OFFSET, predictionSeconds)

    fun shouldAnimate(renderTimestampMs: Long): Boolean {
        val deliveryTimestampMs = renderDeliveryTimestampMs
        return if (deliveryTimestampMs == null) {
            renderTimestampMs - measurementTimestampMs + renderLeadMs < maxPredictionMs
        } else {
            renderTimestampMs - deliveryTimestampMs < maxRenderExtrapolationMs
        }
    }

    /** Starts a bounded render-only extrapolation window without changing tracker state. */
    internal fun deliveredAt(timestampMs: Long): LandmarkRenderFrame =
        LandmarkRenderFrame(
            positions = positions,
            velocities = velocities,
            measurementTimestampMs = measurementTimestampMs,
            predictedOnly = predictedOnly,
            renderLeadMs = renderLeadMs,
            maxPredictionMs = maxPredictionMs,
            renderDeliveryTimestampMs = timestampMs.coerceAtLeast(measurementTimestampMs),
            maxRenderExtrapolationMs = maxRenderExtrapolationMs,
        )

    /** Smooths only the visual correction between delivered results; tracker data stays intact. */
    internal fun smoothCorrectionFrom(
        previous: LandmarkRenderFrame?,
        timestampMs: Long,
    ): LandmarkRenderFrame {
        if (previous == null || previous.positions.size != positions.size) return this

        val previousPredictionSeconds = previous.predictionSeconds(timestampMs)
        val currentPredictionSeconds = predictionSeconds(timestampMs)
        val correctionOffsets = FloatArray(positions.size)
        var centroidCorrectionX = 0f
        var centroidCorrectionY = 0f
        var landmarkCount = 0
        var index = 0
        while (index < positions.size) {
            val xCorrection = previous.predictedCoordinate(
                index / COORDINATE_COUNT,
                X_OFFSET,
                previousPredictionSeconds,
            ) - predictedCoordinate(index / COORDINATE_COUNT, X_OFFSET, currentPredictionSeconds)
            val yCorrection = previous.predictedCoordinate(
                index / COORDINATE_COUNT,
                Y_OFFSET,
                previousPredictionSeconds,
            ) - predictedCoordinate(index / COORDINATE_COUNT, Y_OFFSET, currentPredictionSeconds)
            correctionOffsets[index] = xCorrection.coerceIn(
                -MAX_RENDER_CORRECTION,
                MAX_RENDER_CORRECTION,
            )
            correctionOffsets[index + 1] = yCorrection.coerceIn(
                -MAX_RENDER_CORRECTION,
                MAX_RENDER_CORRECTION,
            )
            correctionOffsets[index + 2] = (
                previous.predictedCoordinate(
                    index / COORDINATE_COUNT,
                    Z_OFFSET,
                    previousPredictionSeconds,
                ) - predictedCoordinate(
                    index / COORDINATE_COUNT,
                    Z_OFFSET,
                    currentPredictionSeconds,
                )
            ).coerceIn(-MAX_RENDER_CORRECTION, MAX_RENDER_CORRECTION)
            centroidCorrectionX += xCorrection
            centroidCorrectionY += yCorrection
            landmarkCount++
            index += COORDINATE_COUNT
        }
        centroidCorrectionX /= landmarkCount
        centroidCorrectionY /= landmarkCount
        if (
            centroidCorrectionX * centroidCorrectionX +
            centroidCorrectionY * centroidCorrectionY >
            MAX_TRANSITION_CENTROID_DISTANCE * MAX_TRANSITION_CENTROID_DISTANCE
        ) {
            return this
        }

        return LandmarkRenderFrame(
            positions = positions,
            velocities = velocities,
            measurementTimestampMs = measurementTimestampMs,
            predictedOnly = predictedOnly,
            renderLeadMs = renderLeadMs,
            maxPredictionMs = maxPredictionMs,
            renderDeliveryTimestampMs = renderDeliveryTimestampMs,
            maxRenderExtrapolationMs = maxRenderExtrapolationMs,
            renderCorrectionOffsets = correctionOffsets,
        )
    }

    private fun predictedCoordinate(
        index: Int,
        coordinateOffset: Int,
        predictionSeconds: Float,
    ): Float {
        require(index in 0 until size)
        val coordinateIndex = index * COORDINATE_COUNT + coordinateOffset
        val predicted = positions[coordinateIndex] + velocities[coordinateIndex] * predictionSeconds
        val correctionOffsets = renderCorrectionOffsets ?: return predicted
        val deliveryTimestampMs = renderDeliveryTimestampMs ?: return predicted
        val alignmentPredictionSeconds = (
            deliveryTimestampMs - measurementTimestampMs + renderLeadMs
        ).coerceIn(0L, maxPredictionMs) / MILLIS_PER_SECOND
        val correctionElapsedMs = (
            (predictionSeconds - alignmentPredictionSeconds) * MILLIS_PER_SECOND
        ).coerceAtLeast(0f)
        val progress = (correctionElapsedMs / RENDER_CORRECTION_DURATION_MS).coerceIn(0f, 1f)
        val smoothProgress = progress * progress * (3f - 2f * progress)
        return predicted + correctionOffsets[coordinateIndex] * (1f - smoothProgress)
    }

    companion object {
        internal const val COORDINATE_COUNT = 3
        private const val X_OFFSET = 0
        private const val Y_OFFSET = 1
        private const val Z_OFFSET = 2
        private const val DISPLAY_MARGIN = 0.25f
        // One display frame is enough to bridge a 30 FPS ML result without carrying stale
        // velocity through most of the next camera interval.
        private const val DEFAULT_MAX_RENDER_EXTRAPOLATION_MS = 20L
        private const val RENDER_CORRECTION_DURATION_MS = 16f
        private const val MAX_RENDER_CORRECTION = 0.12f
        private const val MAX_TRANSITION_CENTROID_DISTANCE = 0.15f
        private const val MILLIS_PER_SECOND = 1_000f
    }
}
