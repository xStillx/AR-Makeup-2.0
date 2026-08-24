package com.example.armakeup.tracking.face

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceSurfaceLipTopologyTest {

    @Test
    fun `keeps only face triangles bridging the outer and inner lip loops`() {
        val descriptor = FaceTopologyDescriptor("lip-band", 1, 11)
        val surface = FaceSurfaceTopology.of(
            descriptor,
            shortArrayOf(
                0, 1, 8,
                1, 2, 8,
                4, 5, 9,
                2, 3, 10,
            ),
        )
        val canonical = FaceLandmarkSet.of(
            descriptor,
            FaceCoordinateSpace.FACE_LOCAL_METERS,
            floatArrayOf(
                -2f, 0f, 0f,
                0f, -1f, 0f,
                2f, 0f, 0f,
                0f, 1f, 0f,
                -0.8f, 0f, 0f,
                0f, -0.35f, 0f,
                0.8f, 0f, 0f,
                0f, 0.35f, 0f,
                -1f, -0.35f, 0f,
                0f, 0f, 0f,
                3f, 0f, 0f,
            ),
        )

        assertArrayEquals(
            shortArrayOf(0, 1, 8, 1, 2, 8),
            FaceSurfaceLipTopology.extractBandIndices(
                surface = surface,
                canonicalLandmarks = canonical,
                outerContour = intArrayOf(0, 1, 2, 3),
                innerContour = intArrayOf(4, 5, 6, 7),
            ),
        )
    }
}
