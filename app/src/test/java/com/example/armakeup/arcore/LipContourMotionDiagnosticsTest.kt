package com.example.armakeup.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LipContourMotionDiagnosticsTest {
    @Test
    fun similarityMotionDoesNotAppearAsLipLocalMotion() {
        val diagnostics = diagnostics()
        assertNull(
            diagnostics.record(
                anchors = anchors(),
                rawOuter = outer(),
                rawInner = inner(),
                stabilizedOuter = outer(),
                stabilizedInner = inner(),
                timestampMs = 1_000L,
            ),
        )
        val angle = 0.31f
        val transformedAnchors = transform(anchors(), angle, scale = 1.17f, 0.08f, -0.04f)
        val transformedOuter = transform(outer(), angle, scale = 1.17f, 0.08f, -0.04f)
        val transformedInner = transform(inner(), angle, scale = 1.17f, 0.08f, -0.04f)

        val sample = diagnostics.record(
            anchors = transformedAnchors,
            rawOuter = transformedOuter,
            rawInner = transformedInner,
            stabilizedOuter = transformedOuter,
            stabilizedInner = transformedInner,
            timestampMs = 1_033L,
        )

        assertEquals(0f, checkNotNull(sample).rawLocalStepRms, EPSILON)
        assertEquals(0f, sample.stabilizedLocalStepRms, EPSILON)
        assertEquals(0f, sample.filterCorrectionRms, EPSILON)
    }

    @Test
    fun reportsExpressionStepAndFilterCorrectionSeparately() {
        val diagnostics = diagnostics()
        diagnostics.record(
            anchors = anchors(),
            rawOuter = outer(),
            rawInner = inner(),
            stabilizedOuter = outer(),
            stabilizedInner = inner(),
            timestampMs = 1_000L,
        )
        val rawOuter = outer().also { it[7] -= 0.04f }
        val rawInner = inner().also { it[7] -= 0.03f }
        val stabilizedOuter = outer().also { it[7] -= 0.02f }
        val stabilizedInner = inner().also { it[7] -= 0.015f }

        val sample = checkNotNull(
            diagnostics.record(
                anchors = anchors(),
                rawOuter = rawOuter,
                rawInner = rawInner,
                stabilizedOuter = stabilizedOuter,
                stabilizedInner = stabilizedInner,
                timestampMs = 1_033L,
            ),
        )

        assertTrue(sample.rawLocalStepRms > sample.stabilizedLocalStepRms)
        assertTrue(sample.filterCorrectionRms > 0f)
        assertTrue(sample.rawMouthAperture > sample.stabilizedMouthAperture)
        assertTrue(sample.rawUpperBandThickness > sample.stabilizedUpperBandThickness)
    }

    @Test
    fun invalidOrLongGapResetsMeasurementHistory() {
        val diagnostics = diagnostics()
        diagnostics.record(
            anchors(),
            outer(),
            inner(),
            outer(),
            inner(),
            timestampMs = 1_000L,
        )

        assertNull(
            diagnostics.record(
                anchors(),
                outer(),
                inner(),
                outer(),
                inner(),
                timestampMs = 1_300L,
            ),
        )
        assertNull(
            diagnostics.record(
                FloatArray(0),
                outer(),
                inner(),
                outer(),
                inner(),
                timestampMs = 1_333L,
            ),
        )
    }

    private fun diagnostics() = LipContourMotionDiagnostics(
        logIntervalMs = 100_000L,
        logger = {},
    )

    private fun anchors() = floatArrayOf(
        0.20f, 0.30f,
        0.30f, 0.30f,
        0.70f, 0.30f,
        0.80f, 0.30f,
    )

    private fun outer() = floatArrayOf(
        0.35f, 0.60f,
        0.50f, 0.67f,
        0.65f, 0.60f,
        0.50f, 0.52f,
    )

    private fun inner() = floatArrayOf(
        0.40f, 0.60f,
        0.50f, 0.63f,
        0.60f, 0.60f,
        0.50f, 0.56f,
    )

    private fun transform(
        points: FloatArray,
        angle: Float,
        scale: Float,
        translateX: Float,
        translateY: Float,
    ): FloatArray {
        val cosine = cos(angle)
        val sine = sin(angle)
        return points.copyOf().also { transformed ->
            var coordinate = 0
            while (coordinate < points.size) {
                val x = points[coordinate]
                val y = points[coordinate + 1]
                transformed[coordinate] = scale * (cosine * x - sine * y) + translateX
                transformed[coordinate + 1] = scale * (sine * x + cosine * y) + translateY
                coordinate += 2
            }
        }
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
