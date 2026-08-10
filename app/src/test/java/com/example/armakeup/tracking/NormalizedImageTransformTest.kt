package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NormalizedImageTransformTest {

    @Test
    fun clockwiseQuarterTurnMapsRawBufferIntoPortraitCoordinates() {
        val transform = NormalizedImageTransform(90, mirrorHorizontal = false)

        assertPoint(transform, x = 0.2f, y = 0.7f, expectedX = 0.3f, expectedY = 0.2f)
    }

    @Test
    fun counterClockwiseQuarterTurnIsNormalizedTo270Degrees() {
        val transform = NormalizedImageTransform(-90, mirrorHorizontal = false)

        assertPoint(transform, x = 0.2f, y = 0.7f, expectedX = 0.7f, expectedY = 0.8f)
    }

    @Test
    fun halfTurnMapsBothAxes() {
        val transform = NormalizedImageTransform(180, mirrorHorizontal = false)

        assertPoint(transform, x = 0.2f, y = 0.7f, expectedX = 0.8f, expectedY = 0.3f)
    }

    @Test
    fun frontCameraMirrorIsAppliedAfterRotation() {
        val transform = NormalizedImageTransform(270, mirrorHorizontal = true)

        assertPoint(transform, x = 0.2f, y = 0.7f, expectedX = 0.3f, expectedY = 0.8f)
    }

    @Test
    fun rejectsNonRightAngleRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedImageTransform(45, mirrorHorizontal = false)
        }
    }

    private fun assertPoint(
        transform: NormalizedImageTransform,
        x: Float,
        y: Float,
        expectedX: Float,
        expectedY: Float,
    ) {
        assertEquals(expectedX, transform.mapX(x, y), EPSILON)
        assertEquals(expectedY, transform.mapY(x, y), EPSILON)
    }

    companion object {
        private const val EPSILON = 0.001f
    }
}
