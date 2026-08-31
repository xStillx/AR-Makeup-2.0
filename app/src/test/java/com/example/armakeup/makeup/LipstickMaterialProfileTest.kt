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

    @Test
    fun finishesProgressFromDiffuseMatteToWetGloss() {
        val matte = ReferenceLipstickOptics.matte
        val satin = ReferenceLipstickOptics.satin
        val gloss = ReferenceLipstickOptics.gloss

        assertTrue(matte.roughness > satin.roughness)
        assertTrue(satin.roughness > gloss.roughness)
        assertTrue(matte.specularStrength < satin.specularStrength)
        assertTrue(satin.specularStrength < gloss.specularStrength)
        assertTrue(matte.highlightRetention < satin.highlightRetention)
        assertTrue(satin.highlightRetention < gloss.highlightRetention)
        assertTrue(matte.microTextureRetention > gloss.microTextureRetention)
        assertTrue(satin.surfaceDetailRetention > gloss.surfaceDetailRetention)
        assertTrue(matte.wetInnerEdgeStrength < gloss.wetInnerEdgeStrength)
    }

    @Test
    fun matteProfileProducesOpaqueCoreWithoutWetReflection() {
        val profile = ReferenceLipstickRenderProfiles.matte

        assertEquals(LipstickPigmentPalette.PRODUCT_ROSE, profile.pigmentPalette)
        assertTrue(
            ReferenceMatteLipstickProfile.upper.effectiveCoreCoverage *
                profile.coverageMultiplier >= 1f,
        )
        assertTrue(
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage *
                profile.coverageMultiplier >= 1f,
        )
        assertEquals(1f, profile.optics.roughness, 0f)
        assertEquals(0f, profile.optics.specularStrength, 0f)
        assertEquals(0f, profile.optics.highlightRetention, 0f)
        assertEquals(0f, profile.optics.wetInnerEdgeStrength, 0f)
        assertTrue(profile.optics.microTextureRetention > 0f)
        assertEquals(1f, profile.optics.surfaceDetailRetention, 0f)
        assertTrue(profile.luminancePreservation > 0.8f)
    }

    @Test
    fun satinProfileIsRichButLighterAndSofterThanMatte() {
        val matte = ReferenceLipstickRenderProfiles.matte
        val satin = ReferenceLipstickRenderProfiles.satin
        val lowerCoreCoverage =
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage *
                satin.coverageMultiplier

        assertEquals(LipstickPigmentPalette.PRODUCT_ROSE, satin.pigmentPalette)
        assertTrue(satin.coverageMultiplier < matte.coverageMultiplier)
        assertTrue(satin.coverageMultiplier > 1f)
        assertTrue(lowerCoreCoverage > 0.75f)
        assertTrue(lowerCoreCoverage < 1f)
        assertTrue(satin.luminancePreservation > matte.luminancePreservation)
        assertTrue(satin.luminancePreservation < 1f)
        assertTrue(satin.optics.roughness > 0.5f)
        assertTrue(satin.optics.specularStrength in 0.1f..0.25f)
        assertTrue(satin.optics.wetInnerEdgeStrength < 0.1f)
        assertTrue(satin.optics.microTextureRetention < matte.optics.microTextureRetention)
        assertEquals(1f, satin.optics.surfaceDetailRetention, 0f)
    }

    @Test
    fun glossProducesDenseLacquerWithSmoothedSurfaceAndWetReflection() {
        val matte = ReferenceLipstickRenderProfiles.matte
        val gloss = ReferenceLipstickRenderProfiles.gloss
        val lowerCoreCoverage =
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage *
                gloss.coverageMultiplier

        assertEquals(LipstickPigmentPalette.PRODUCT_ROSE, gloss.pigmentPalette)
        assertTrue(gloss.coverageMultiplier < matte.coverageMultiplier)
        assertTrue(gloss.coverageMultiplier >= 1.5f)
        assertTrue(lowerCoreCoverage > 0.8f)
        assertTrue(lowerCoreCoverage < 1f)
        assertTrue(gloss.luminancePreservation in 0.85f..0.95f)
        assertTrue(gloss.optics.roughness <= 0.15f)
        assertTrue(gloss.optics.specularStrength >= 0.75f)
        assertEquals(1f, gloss.optics.highlightRetention, 0f)
        assertTrue(gloss.optics.microTextureRetention <= 0.2f)
        assertTrue(gloss.optics.surfaceDetailRetention <= 0.2f)
        assertTrue(gloss.optics.wetInnerEdgeStrength >= 0.6f)
    }

    @Test
    fun trackingTestProfileIsBrightAndOpaqueWithoutChangingLipBoundaries() {
        val profile = ReferenceLipstickRenderProfiles.trackingTest

        assertEquals(LipstickPigmentPalette.TRACKING_MAGENTA, profile.pigmentPalette)
        assertTrue(
            ReferenceMatteLipstickProfile.upper.effectiveCoreCoverage *
                profile.coverageMultiplier >= 1f,
        )
        assertTrue(
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage *
                profile.coverageMultiplier >= 1f,
        )
        assertEquals(0f, profile.luminancePreservation, 0f)
        assertEquals(1f, profile.optics.roughness, 0f)
        assertEquals(0f, profile.optics.specularStrength, 0f)
        assertEquals(0f, profile.optics.highlightRetention, 0f)
        assertEquals(0f, profile.optics.microTextureRetention, 0f)
        assertEquals(0f, profile.optics.wetInnerEdgeStrength, 0f)
        assertEquals(0f, profile.optics.surfaceDetailRetention, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun physicallyInvalidRoughnessIsRejected() {
        LipstickOpticalProfile(
            roughness = 0f,
            specularStrength = 0.2f,
            highlightRetention = 0.5f,
            microTextureRetention = 0.8f,
            wetInnerEdgeStrength = 0.1f,
            surfaceDetailRetention = 0.8f,
        )
    }
}
