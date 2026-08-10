package com.example.armakeup.tracking

/** Immutable landmark state that can be extrapolated at the actual render timestamp. */
class LandmarkRenderFrame internal constructor(
    private val positions: FloatArray,
    private val velocities: FloatArray,
    val measurementTimestampMs: Long,
    val predictedOnly: Boolean,
    private val renderLeadMs: Long,
    private val maxPredictionMs: Long,
) {
    init {
        require(positions.isNotEmpty() && positions.size % COORDINATE_COUNT == 0)
        require(positions.size == velocities.size)
    }

    val size: Int = positions.size / COORDINATE_COUNT

    fun predictionSeconds(renderTimestampMs: Long): Float =
        (renderTimestampMs - measurementTimestampMs + renderLeadMs)
            .coerceIn(0L, maxPredictionMs) / MILLIS_PER_SECOND

    fun x(index: Int, predictionSeconds: Float): Float =
        predictedCoordinate(index, X_OFFSET, predictionSeconds)
            .coerceIn(-DISPLAY_MARGIN, 1f + DISPLAY_MARGIN)

    fun y(index: Int, predictionSeconds: Float): Float =
        predictedCoordinate(index, Y_OFFSET, predictionSeconds)
            .coerceIn(-DISPLAY_MARGIN, 1f + DISPLAY_MARGIN)

    fun z(index: Int, predictionSeconds: Float): Float =
        predictedCoordinate(index, Z_OFFSET, predictionSeconds)

    fun shouldAnimate(renderTimestampMs: Long): Boolean =
        renderTimestampMs - measurementTimestampMs + renderLeadMs < maxPredictionMs

    private fun predictedCoordinate(
        index: Int,
        coordinateOffset: Int,
        predictionSeconds: Float,
    ): Float {
        require(index in 0 until size)
        val coordinateIndex = index * COORDINATE_COUNT + coordinateOffset
        return positions[coordinateIndex] + velocities[coordinateIndex] * predictionSeconds
    }

    companion object {
        internal const val COORDINATE_COUNT = 3
        private const val X_OFFSET = 0
        private const val Y_OFFSET = 1
        private const val Z_OFFSET = 2
        private const val DISPLAY_MARGIN = 0.25f
        private const val MILLIS_PER_SECOND = 1_000f
    }
}
