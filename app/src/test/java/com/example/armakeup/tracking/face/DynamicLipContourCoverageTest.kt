package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicLipContourCoverageTest {

    @Test
    fun `packs paired current contours opacity and geometry-derived feather`() {
        val outerValues = FloatArray(DynamicLipContourCoverage.POINT_COUNT * 2)
        val innerValues = FloatArray(DynamicLipContourCoverage.POINT_COUNT * 2)
        repeat(DynamicLipContourCoverage.POINT_COUNT) { index ->
            outerValues[index * 2] = index * 0.01f
            outerValues[index * 2 + 1] = 0.4f
            innerValues[index * 2] = index * 0.01f
            innerValues[index * 2 + 1] = 0.5f
        }
        val destination = FloatArray(DynamicLipContourCoverage.FLOAT_COUNT)

        DynamicLipContourCoverage.write(
            outer = FaceRegionGeometry.of(FaceRegion.LIPS_OUTER, outerValues),
            inner = FaceRegionGeometry.of(FaceRegion.LIPS_INNER, innerValues),
            opacity = 0.65f,
            destination = destination,
        )

        assertEquals(0f, destination[0], 0f)
        assertEquals(0.4f, destination[1], 0f)
        assertEquals(0f, destination[2], 0f)
        assertEquals(0.5f, destination[3], 0f)
        val parameterIndex = DynamicLipContourCoverage.POINT_COUNT * 4
        assertEquals(1f, destination[parameterIndex], 0f)
        assertEquals(0.65f, destination[DynamicLipContourCoverage.OPACITY_INDEX], 0f)
        assertTrue(destination[parameterIndex + 2] > 0f)
        assertEquals(
            DynamicLipContourCoverage.POINT_COUNT.toFloat(),
            destination[parameterIndex + 3],
            0f,
        )
    }
}
