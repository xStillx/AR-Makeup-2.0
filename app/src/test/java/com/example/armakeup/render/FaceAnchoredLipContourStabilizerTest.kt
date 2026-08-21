package com.example.armakeup.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAnchoredLipContourStabilizerTest {
    @Test
    fun globalTranslationIsAppliedWithoutLocalFilterLag() {
        val stabilizer = FaceAnchoredLipContourStabilizer(localCutoffHz = 1f)
        val firstAnchors = anchors()
        val firstOuter = contour()
        val firstInner = innerContour()
        stabilizer.stabilize(firstAnchors, firstOuter, firstInner, timestampMs = 1_000L)

        val translatedAnchors = translated(anchors(), x = 0.08f, y = -0.04f)
        val translatedOuter = translated(contour(), x = 0.08f, y = -0.04f)
        val translatedInner = translated(innerContour(), x = 0.08f, y = -0.04f)
        stabilizer.stabilize(
            translatedAnchors,
            translatedOuter,
            translatedInner,
            timestampMs = 1_016L,
        )

        assertPointsEqual(translated(contour(), 0.08f, -0.04f), translatedOuter)
        assertPointsEqual(translated(innerContour(), 0.08f, -0.04f), translatedInner)
    }

    @Test
    fun faceLocalContourNoiseIsAttenuated() {
        val stabilizer = FaceAnchoredLipContourStabilizer(localCutoffHz = 4f)
        val outer = contour()
        val inner = innerContour()
        stabilizer.stabilize(anchors(), outer, inner, timestampMs = 1_000L)

        val noisyOuter = contour().also {
            it[0] += 0.02f
            it[1] -= 0.01f
        }
        stabilizer.stabilize(anchors(), noisyOuter, innerContour(), timestampMs = 1_016L)

        assertTrue(noisyOuter[0] > contour()[0])
        assertTrue(noisyOuter[0] < contour()[0] + 0.01f)
        assertTrue(noisyOuter[1] < contour()[1])
        assertTrue(noisyOuter[1] > contour()[1] - 0.005f)
    }

    @Test
    fun rotationAndScaleAreTransportedWithoutDistortingLipShape() {
        val stabilizer = FaceAnchoredLipContourStabilizer(localCutoffHz = 1f)
        val outer = contour()
        val inner = innerContour()
        stabilizer.stabilize(anchors(), outer, inner, timestampMs = 1_000L)

        val transformedAnchors = rotateScale(anchors(), scale = 1.1f)
        val transformedOuter = rotateScale(contour(), scale = 1.1f)
        val transformedInner = rotateScale(innerContour(), scale = 1.1f)
        stabilizer.stabilize(
            transformedAnchors,
            transformedOuter,
            transformedInner,
            timestampMs = 1_016L,
        )

        assertPointsEqual(rotateScale(contour(), 1.1f), transformedOuter)
        assertPointsEqual(rotateScale(innerContour(), 1.1f), transformedInner)
    }

    @Test
    fun longGapResetsInsteadOfBlendingStaleContour() {
        val stabilizer = FaceAnchoredLipContourStabilizer(maximumFrameGapMs = 50L)
        stabilizer.stabilize(anchors(), contour(), innerContour(), timestampMs = 1_000L)
        val changedOuter = contour().also { it[0] += 0.04f }

        stabilizer.stabilize(anchors(), changedOuter, innerContour(), timestampMs = 1_100L)

        assertEquals(contour()[0] + 0.04f, changedOuter[0], EPSILON)
    }

    @Test
    fun repeatedCameraTimestampKeepsGeometryBitIdentical() {
        val stabilizer = FaceAnchoredLipContourStabilizer(localCutoffHz = 4f)
        val firstOuter = contour()
        val firstInner = innerContour()
        stabilizer.stabilize(anchors(), firstOuter, firstInner, timestampMs = 1_000L)

        val changedOuter = translated(contour(), x = 0.04f, y = 0f)
        val changedInner = translated(innerContour(), x = 0.04f, y = 0f)
        stabilizer.stabilize(
            translated(anchors(), x = 0.04f, y = 0f),
            changedOuter,
            changedInner,
            timestampMs = 1_000L,
        )

        assertPointsEqual(contour(), changedOuter)
        assertPointsEqual(innerContour(), changedInner)
    }

    private fun anchors() = floatArrayOf(
        0.30f, 0.35f,
        0.70f, 0.35f,
        0.50f, 0.50f,
        0.35f, 0.70f,
        0.65f, 0.70f,
    )

    private fun contour() = floatArrayOf(
        0.40f, 0.58f,
        0.50f, 0.55f,
        0.60f, 0.58f,
        0.50f, 0.62f,
    )

    private fun innerContour() = floatArrayOf(
        0.46f, 0.585f,
        0.50f, 0.575f,
        0.54f, 0.585f,
        0.50f, 0.595f,
    )

    private fun translated(points: FloatArray, x: Float, y: Float): FloatArray =
        points.copyOf().also { translated ->
            var index = 0
            while (index < translated.size) {
                translated[index] += x
                translated[index + 1] += y
                index += 2
            }
        }

    private fun rotateScale(points: FloatArray, scale: Float): FloatArray =
        points.copyOf().also { transformed ->
            var index = 0
            while (index < transformed.size) {
                val x = points[index] - 0.5f
                val y = points[index + 1] - 0.5f
                transformed[index] = 0.5f - scale * y
                transformed[index + 1] = 0.5f + scale * x
                index += 2
            }
        }

    private fun assertPointsEqual(expected: FloatArray, actual: FloatArray) {
        expected.indices.forEach { index -> assertEquals(expected[index], actual[index], EPSILON) }
    }

    companion object {
        private const val EPSILON = 1e-4f
    }
}
