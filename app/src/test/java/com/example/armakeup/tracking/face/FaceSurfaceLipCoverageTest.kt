package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSurfaceLipCoverageTest {

    @Test
    fun `builds feathered band only between canonical outer and inner loops`() {
        val topology = FaceTopologyDescriptor("uv-lips", 1, 12)
        val coordinates = FaceSurfaceTextureCoordinates.of(
            topology,
            floatArrayOf(
                -2f, -2f,
                2f, -2f,
                2f, 2f,
                -2f, 2f,
                -0.5f, -0.5f,
                0.5f, -0.5f,
                0.5f, 0.5f,
                -0.5f, 0.5f,
                0f, -1.2f,
                0f, 0f,
                3f, 0f,
                -2f, 0f,
            ),
        )

        val coverage = FaceSurfaceLipCoverage.build(
            textureCoordinates = coordinates,
            outerContour = intArrayOf(0, 1, 2, 3),
            innerContour = intArrayOf(4, 5, 6, 7),
        )

        assertEquals(0f, coverage[0], 0f)
        assertEquals(0f, coverage[4], 0f)
        assertTrue(coverage[8] > 0f)
        assertTrue(coverage[8] <= 0.25f)
        assertEquals(0f, coverage[9], 0f)
        assertEquals(0f, coverage[10], 0f)
        assertEquals(0f, coverage[11], 0f)
    }
}
