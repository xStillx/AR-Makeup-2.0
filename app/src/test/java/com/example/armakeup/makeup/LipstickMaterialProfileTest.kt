package com.example.armakeup.makeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LipstickMaterialProfileTest {

    @Test
    fun productPigmentUsesSharedClassicRed999SourceSrgbReference() {
        assertEquals(0x9E2620, ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_SRGB_HEX)
        assertEquals(158, ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_RED_8BIT)
        assertEquals(38, ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_GREEN_8BIT)
        assertEquals(32, ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_BLUE_8BIT)
        assertEquals(
            158f / 255f,
            ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_RED_SRGB,
            0.000001f,
        )
        assertEquals(
            38f / 255f,
            ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_GREEN_SRGB,
            0.000001f,
        )
        assertEquals(
            32f / 255f,
            ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_BLUE_SRGB,
            0.000001f,
        )
    }

    @Test
    fun satinPigmentUsesRequestedB8202dSourceSrgbReference() {
        assertEquals(0xB8202D, ReferenceLipstickPigments.SATIN_RED_B8202D_SRGB_HEX)
        assertEquals(184, ReferenceLipstickPigments.SATIN_RED_B8202D_RED_8BIT)
        assertEquals(32, ReferenceLipstickPigments.SATIN_RED_B8202D_GREEN_8BIT)
        assertEquals(45, ReferenceLipstickPigments.SATIN_RED_B8202D_BLUE_8BIT)
        assertEquals(
            184f / 255f,
            ReferenceLipstickPigments.SATIN_RED_B8202D_RED_SRGB,
            0.000001f,
        )
        assertEquals(
            32f / 255f,
            ReferenceLipstickPigments.SATIN_RED_B8202D_GREEN_SRGB,
            0.000001f,
        )
        assertEquals(
            45f / 255f,
            ReferenceLipstickPigments.SATIN_RED_B8202D_BLUE_SRGB,
            0.000001f,
        )
    }

    @Test
    fun productProfilesBoundCameraDrivenLuminanceGain() {
        listOf(
            ReferenceLipstickRenderProfiles.matte,
            ReferenceLipstickRenderProfiles.satin,
            ReferenceLipstickRenderProfiles.gloss,
        ).forEach { profile ->
            assertEquals(0.55f, profile.minimumLuminanceGain, 0f)
            assertEquals(1.65f, profile.maximumLuminanceGain, 0f)
        }
    }

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
        assertEquals(0f, matte.satinGlowStrength, 0f)
        assertEquals(1f, satin.satinGlowStrength, 0f)
        assertEquals(0f, gloss.satinGlowStrength, 0f)
        assertTrue(matte.wetInnerEdgeStrength < gloss.wetInnerEdgeStrength)
    }

    @Test
    fun matteProfileProducesOpaqueCoreWithoutWetReflection() {
        val profile = ReferenceLipstickRenderProfiles.matte

        assertEquals(LipstickPigmentPalette.PRODUCT_CLASSIC_RED_999, profile.pigmentPalette)
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
        assertEquals(0f, profile.optics.satinGlowStrength, 0f)
        assertTrue(profile.optics.microTextureRetention > 0f)
        assertEquals(1f, profile.optics.surfaceDetailRetention, 0f)
        assertTrue(profile.luminancePreservation > 0.8f)
    }

    @Test
    fun satinProfilePreservesRichPigmentWithSoftCameraConditionedGlow() {
        val matte = ReferenceLipstickRenderProfiles.matte
        val satin = ReferenceLipstickRenderProfiles.satin
        val lowerCoreCoverage =
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage *
                satin.coverageMultiplier

        assertEquals(LipstickPigmentPalette.SATIN_RED_B8202D, satin.pigmentPalette)
        assertEquals(matte.coverageMultiplier, satin.coverageMultiplier, 0f)
        assertTrue(lowerCoreCoverage >= 1f)
        assertEquals(0.68f, satin.luminancePreservation, 0f)
        assertTrue(satin.luminancePreservation < matte.luminancePreservation)
        assertTrue(satin.luminancePreservation < 1f)
        assertTrue(satin.optics.roughness > 0.7f)
        assertEquals(0.18f, satin.optics.specularStrength, 0f)
        assertTrue(satin.optics.highlightRetention in 0.5f..0.65f)
        assertEquals(0f, satin.optics.wetInnerEdgeStrength, 0f)
        assertTrue(satin.optics.microTextureRetention < matte.optics.microTextureRetention)
        assertTrue(satin.optics.surfaceDetailRetention < matte.optics.surfaceDetailRetention)
        assertTrue(satin.optics.surfaceDetailRetention >
            ReferenceLipstickRenderProfiles.gloss.optics.surfaceDetailRetention)
        assertEquals(1f, satin.optics.satinGlowStrength, 0f)
    }

    @Test
    fun glossProducesDenseLacquerWithSmoothedSurfaceAndWetReflection() {
        val matte = ReferenceLipstickRenderProfiles.matte
        val gloss = ReferenceLipstickRenderProfiles.gloss
        val lowerCoreCoverage =
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage *
                gloss.coverageMultiplier

        assertEquals(LipstickPigmentPalette.PRODUCT_CLASSIC_RED_999, gloss.pigmentPalette)
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
        assertEquals(0f, gloss.optics.satinGlowStrength, 0f)
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
        assertEquals(1f, profile.minimumLuminanceGain, 0f)
        assertEquals(1f, profile.maximumLuminanceGain, 0f)
        assertEquals(1f, profile.optics.roughness, 0f)
        assertEquals(0f, profile.optics.specularStrength, 0f)
        assertEquals(0f, profile.optics.highlightRetention, 0f)
        assertEquals(0f, profile.optics.microTextureRetention, 0f)
        assertEquals(0f, profile.optics.wetInnerEdgeStrength, 0f)
        assertEquals(0f, profile.optics.surfaceDetailRetention, 0f)
        assertEquals(0f, profile.optics.satinGlowStrength, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun physicallyInvalidRoughnessIsRejected() {
        LipstickOpticalProfile(
            roughness = 0f,
            specularStrength = 0.2f,
            highlightRetention = 0.5f,
            microTextureRetention = 0.8f,
            surfaceDetailRetention = 0.8f,
            satinGlowStrength = 0.2f,
            wetInnerEdgeStrength = 0.1f,
        )
    }
}
