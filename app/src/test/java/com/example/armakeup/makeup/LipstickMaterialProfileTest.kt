package com.example.armakeup.makeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LipstickMaterialProfileTest {

    @Test
    fun referenceProfileIsVisibleWhileKeepingCameraTextureAtLipCore() {
        assertEquals(
            0.569f,
            ReferenceMatteLipstickProfile.upper.effectiveCoreCoverage,
            0.002f,
        )
        assertEquals(
            0.507f,
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage,
            0.002f,
        )
        assertTrue(
            ReferenceMatteLipstickProfile.upper.effectiveCoreCoverage >
                ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage,
        )
        assertTrue(ReferenceMatteLipstickProfile.upper.effectiveCoreCoverage < 0.59f)
        assertTrue(ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage > 0.49f)
    }

    @Test
    fun pigmentBuildsTowardLipCoreWithoutHardOpaquePass() {
        listOf(
            ReferenceMatteLipstickProfile.upper,
            ReferenceMatteLipstickProfile.lower,
        ).forEach { profile ->
            assertTrue(profile.edgeCoverage < profile.midCoverage)
            assertTrue(profile.midCoverage < profile.coreCoverage)
            assertTrue(profile.coreCoverage < 128)
            assertTrue(profile.matteCoverage < 10)
        }
    }

    @Test
    fun featherInsetsProgressFromContourTowardLipInterior() {
        assertTrue(ReferenceMatteLipstickProfile.EDGE_INSET_FRACTION > 0f)
        assertTrue(
            ReferenceMatteLipstickProfile.EDGE_INSET_FRACTION <
                ReferenceMatteLipstickProfile.MID_INSET_FRACTION,
        )
        assertTrue(
            ReferenceMatteLipstickProfile.MID_INSET_FRACTION <
                ReferenceMatteLipstickProfile.CORE_INSET_FRACTION,
        )
        assertTrue(ReferenceMatteLipstickProfile.CORE_INSET_FRACTION < 0.25f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun coverageOutsideAlphaRangeIsRejected() {
        LipstickCoverageProfile(
            edgeCoverage = -1,
            midCoverage = 40,
            coreCoverage = 80,
            matteCoverage = 8,
        )
    }
}
