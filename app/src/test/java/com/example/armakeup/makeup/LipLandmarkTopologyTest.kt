package com.example.armakeup.makeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LipLandmarkTopologyTest {

    @Test
    fun outerAndInnerContoursHaveOneToOneCorrespondence() {
        assertEquals(20, LipLandmarkTopology.outerContour.size)
        assertEquals(
            LipLandmarkTopology.outerContour.size,
            LipLandmarkTopology.innerContour.size,
        )
    }

    @Test
    fun contoursUseDistinctLandmarksAndKnownLipCorners() {
        assertEquals(61, LipLandmarkTopology.outerContour.first())
        assertTrue(LipLandmarkTopology.outerContour.contains(291))
        assertEquals(78, LipLandmarkTopology.innerContour.first())
        assertTrue(LipLandmarkTopology.innerContour.contains(308))
        assertFalse(
            LipLandmarkTopology.outerContour.toSet()
                .intersect(LipLandmarkTopology.innerContour.toSet())
                .isNotEmpty(),
        )
    }
}
