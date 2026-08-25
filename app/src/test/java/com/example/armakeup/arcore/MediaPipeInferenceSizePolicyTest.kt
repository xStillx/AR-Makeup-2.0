package com.example.armakeup.arcore

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPipeInferenceSizePolicyTest {
    @Test
    fun landscapeInputIsBoundedWithoutChangingAspect() {
        assertEquals(
            MediaPipeInferenceSize(480, 360),
            MediaPipeInferenceSizePolicy.fitWithin(640, 480, maximumLongEdge = 480),
        )
    }

    @Test
    fun portraitInputUsesTheSameLongEdgePolicy() {
        assertEquals(
            MediaPipeInferenceSize(360, 480),
            MediaPipeInferenceSizePolicy.fitWithin(480, 640, maximumLongEdge = 480),
        )
    }

    @Test
    fun smallerInputIsNotUpscaled() {
        assertEquals(
            MediaPipeInferenceSize(320, 240),
            MediaPipeInferenceSizePolicy.fitWithin(320, 240, maximumLongEdge = 480),
        )
    }

    @Test
    fun scaledDimensionsRemainEvenForYuvChromaSampling() {
        val result = MediaPipeInferenceSizePolicy.fitWithin(
            sourceWidth = 641,
            sourceHeight = 481,
            maximumLongEdge = 480,
        )

        assertEquals(0, result.width % 2)
        assertEquals(0, result.height % 2)
        assertEquals(480, maxOf(result.width, result.height))
    }
}
