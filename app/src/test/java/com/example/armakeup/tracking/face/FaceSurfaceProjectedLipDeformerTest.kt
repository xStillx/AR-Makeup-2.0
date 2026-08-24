package com.example.armakeup.tracking.face

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceSurfaceProjectedLipDeformerTest {

    @Test
    fun `moves semantic lip xy while preserving face depth and unrelated vertices`() {
        val topology = FaceTopologyDescriptor("deform", 1, 5)
        val display = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
            floatArrayOf(
                0f, 0f, -0.8f,
                0.1f, 0.1f, -0.6f,
                0.2f, 0.2f, -0.4f,
                0.3f, 0.3f, -0.2f,
                0.4f, 0.4f, 0f,
            ),
        )

        assertArrayEquals(
            floatArrayOf(
                0f, 0f, -0.8f,
                0.7f, 0.8f, -0.6f,
                0.2f, 0.2f, -0.4f,
                0.5f, 0.6f, -0.2f,
                0.4f, 0.4f, 0f,
            ),
            FaceSurfaceProjectedLipDeformer.deform(
                displayLandmarks = display,
                outerGeometry = FaceRegionGeometry.of(
                    FaceRegion.LIPS_OUTER,
                    floatArrayOf(0.7f, 0.8f),
                ),
                innerGeometry = FaceRegionGeometry.of(
                    FaceRegion.LIPS_INNER,
                    floatArrayOf(0.5f, 0.6f),
                ),
                outerIndices = intArrayOf(1),
                innerIndices = intArrayOf(3),
            ),
            0f,
        )
    }
}
