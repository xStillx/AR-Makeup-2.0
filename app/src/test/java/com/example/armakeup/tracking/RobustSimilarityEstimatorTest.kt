package com.example.armakeup.tracking

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustSimilarityEstimatorTest {
    @Test
    fun recoversExactSimilarityMotion() {
        val source = face()
        val target = transform(
            source,
            scale = 1.03f,
            rotationRadians = 0.08f,
            translationX = 0.02f,
            translationY = -0.01f,
        )

        val estimate = RobustSimilarityEstimator().estimate(source, target)

        assertTrue(estimate.isValid)
        assertTrue(estimate.normalizedRmsResidual < 0.0001f)
        assertTrue(estimate.inlierFraction > 0.99f)
        assertTrue(estimate.quality > 0.99f)
        assertMappedPointClose(estimate, source, target, landmarkIndex = 61, tolerance = 0.0001f)
    }

    @Test
    fun downweightsOneCorruptedEyeAnchor() {
        val source = face()
        val target = transform(
            source,
            scale = 1f,
            rotationRadians = 0.02f,
            translationX = 0.015f,
            translationY = -0.005f,
        )
        target[33 * 3] += 0.08f
        target[33 * 3 + 1] -= 0.05f

        val estimate = RobustSimilarityEstimator().estimate(source, target)

        assertTrue(estimate.isValid)
        assertTrue(estimate.inlierFraction < 1f)
        assertTrue(estimate.quality > 0.2f)
        assertMappedPointClose(estimate, source, target, landmarkIndex = 61, tolerance = 0.003f)
    }

    @Test
    fun rejectsGeometryWithoutTheRequiredAnchors() {
        val estimate = RobustSimilarityEstimator().estimate(
            FloatArray(4 * 3),
            FloatArray(4 * 3),
        )

        assertFalse(estimate.isValid)
    }

    private fun assertMappedPointClose(
        estimate: RobustSimilarityEstimate,
        source: FloatArray,
        target: FloatArray,
        landmarkIndex: Int,
        tolerance: Float,
    ) {
        val coordinateIndex = landmarkIndex * 3
        val deltaX = estimate.mapX(source[coordinateIndex], source[coordinateIndex + 1]) -
            target[coordinateIndex]
        val deltaY = estimate.mapY(source[coordinateIndex], source[coordinateIndex + 1]) -
            target[coordinateIndex + 1]
        assertTrue("mapping error was ${hypot(deltaX, deltaY)}", hypot(deltaX, deltaY) <= tolerance)
    }

    private fun face(): FloatArray = FloatArray(478 * 3).also { coordinates ->
        repeat(478) { index ->
            coordinates[index * 3] = 0.5f + (index % 17 - 8) * 0.01f
            coordinates[index * 3 + 1] = 0.5f + ((index / 17) % 17 - 8) * 0.008f
        }
    }

    private fun transform(
        coordinates: FloatArray,
        scale: Float,
        rotationRadians: Float,
        translationX: Float,
        translationY: Float,
    ): FloatArray {
        val cosRotation = cos(rotationRadians)
        val sinRotation = sin(rotationRadians)
        return coordinates.copyOf().also { transformed ->
            var index = 0
            while (index < transformed.size) {
                val localX = coordinates[index] - 0.5f
                val localY = coordinates[index + 1] - 0.5f
                transformed[index] = 0.5f + translationX + scale * (
                    cosRotation * localX - sinRotation * localY
                )
                transformed[index + 1] = 0.5f + translationY + scale * (
                    sinRotation * localX + cosRotation * localY
                )
                index += 3
            }
        }
    }
}
