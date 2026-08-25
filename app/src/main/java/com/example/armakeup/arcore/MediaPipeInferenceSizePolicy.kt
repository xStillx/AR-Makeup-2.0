package com.example.armakeup.arcore

import kotlin.math.roundToInt

internal data class MediaPipeInferenceSize(val width: Int, val height: Int) {
    init {
        require(width > 0)
        require(height > 0)
    }
}

/** Preserves the camera aspect ratio while bounding only the MediaPipe CPU input. */
internal object MediaPipeInferenceSizePolicy {
    fun fitWithin(
        sourceWidth: Int,
        sourceHeight: Int,
        maximumLongEdge: Int,
    ): MediaPipeInferenceSize {
        require(sourceWidth > 0)
        require(sourceHeight > 0)
        require(maximumLongEdge >= MINIMUM_DIMENSION)
        val sourceLongEdge = maxOf(sourceWidth, sourceHeight)
        if (sourceLongEdge <= maximumLongEdge) {
            return MediaPipeInferenceSize(sourceWidth, sourceHeight)
        }
        val scale = maximumLongEdge.toFloat() / sourceLongEdge
        return MediaPipeInferenceSize(
            width = evenDimension((sourceWidth * scale).roundToInt()),
            height = evenDimension((sourceHeight * scale).roundToInt()),
        )
    }

    private fun evenDimension(value: Int): Int =
        value.coerceAtLeast(MINIMUM_DIMENSION) and EVEN_MASK

    private const val MINIMUM_DIMENSION = 2
    private const val EVEN_MASK = -2
}
