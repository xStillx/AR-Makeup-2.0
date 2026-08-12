package com.example.armakeup.render

import org.junit.Assert.assertEquals
import org.junit.Test

class VulkanCameraFrameStateTest {
    @Test
    fun cameraFrameAgeUsesTheSharedSensorClock() {
        assertEquals(
            8_000_000L,
            VulkanCameraFrameState.cameraToLandmarkAgeNs(
                cameraSensorTimestampNs = 1_008_000_000L,
                landmarkSensorTimestampNs = 1_000_000_000L,
            ),
        )
    }

    @Test
    fun futureLandmarkTimestampCannotProduceNegativeAge() {
        assertEquals(
            0L,
            VulkanCameraFrameState.cameraToLandmarkAgeNs(
                cameraSensorTimestampNs = 1_000_000_000L,
                landmarkSensorTimestampNs = 1_001_000_000L,
            ),
        )
    }

    @Test
    fun transformDefensivelyCopiesItsMatrix() {
        val matrix = FloatArray(16) { it.toFloat() }
        val transform = VulkanCameraTransform(
            cropLeft = 0,
            cropTop = 0,
            cropRight = 640,
            cropBottom = 480,
            rotationDegrees = 270,
            mirrorHorizontal = true,
            matrix = matrix,
        )

        matrix[0] = -1f
        val firstRead = transform.matrixCopy()
        firstRead[1] = -1f

        assertEquals(0f, transform.matrixCopy()[0], 0f)
        assertEquals(1f, transform.matrixCopy()[1], 0f)
    }
}
