package com.example.armakeup.makeup

/** Alpha coverage contributed by the successive pigment passes of one lip region. */
internal data class LipstickCoverageProfile(
    val edgeCoverage: Int,
    val midCoverage: Int,
    val coreCoverage: Int,
    val matteCoverage: Int,
) {
    init {
        require(edgeCoverage in ALPHA_RANGE)
        require(midCoverage in ALPHA_RANGE)
        require(coreCoverage in ALPHA_RANGE)
        require(matteCoverage in ALPHA_RANGE)
    }

    /** Combined pigment coverage at the lip core before the separate matte pass. */
    val effectiveCoreCoverage: Float
        get() = 1f -
            (1f - edgeCoverage / MAX_ALPHA) *
            (1f - midCoverage / MAX_ALPHA) *
            (1f - coreCoverage / MAX_ALPHA)

    private companion object {
        private val ALPHA_RANGE = 0..255
        private const val MAX_ALPHA = 255f
    }
}

/**
 * Reference-calibrated classic neutral-red matte material.
 *
 * The upper lip receives slightly more pigment and matte compression because it naturally faces
 * away from the main light. Lower coverage on the lower lip keeps its native highlight and volume.
 */
internal object ReferenceMatteLipstickProfile {
    val upper = LipstickCoverageProfile(
        edgeCoverage = 22,
        midCoverage = 52,
        coreCoverage = 104,
        matteCoverage = 8,
    )
    val lower = LipstickCoverageProfile(
        edgeCoverage = 18,
        midCoverage = 46,
        coreCoverage = 90,
        matteCoverage = 3,
    )

    const val EDGE_INSET_FRACTION = 0.015f
    const val MID_INSET_FRACTION = 0.07f
    const val CORE_INSET_FRACTION = 0.16f
}

/** Three product finishes plus a temporary, debug-only tracking aid. */
internal enum class LipstickFinish {
    MATTE,
    SATIN,
    GLOSS,
    TRACKING_TEST,
}

/**
 * Reference source-sRGB pigments used by product finishes.
 *
 * Pigment identity remains independent from finish optics. Matte keeps the classic 999 calibration
 * reference, the iOS satin reference uses B8202D, the active satin calibration uses C2050E,
 * and the active gloss target uses the user-provided Brown Espresso 515 colour sample. These
 * labels are internal visual references, not claims that the values are official brand colours.
 */
internal object ReferenceLipstickPigments {
    const val PRODUCT_CLASSIC_RED_999_SRGB_HEX = 0x9E2620
    const val PRODUCT_CLASSIC_RED_999_RED_8BIT = 0x9E
    const val PRODUCT_CLASSIC_RED_999_GREEN_8BIT = 0x26
    const val PRODUCT_CLASSIC_RED_999_BLUE_8BIT = 0x20

    const val PRODUCT_CLASSIC_RED_999_RED_SRGB = PRODUCT_CLASSIC_RED_999_RED_8BIT / 255f
    const val PRODUCT_CLASSIC_RED_999_GREEN_SRGB = PRODUCT_CLASSIC_RED_999_GREEN_8BIT / 255f
    const val PRODUCT_CLASSIC_RED_999_BLUE_SRGB = PRODUCT_CLASSIC_RED_999_BLUE_8BIT / 255f

    const val SATIN_RED_B8202D_SRGB_HEX = 0xB8202D
    const val SATIN_RED_B8202D_RED_8BIT = 0xB8
    const val SATIN_RED_B8202D_GREEN_8BIT = 0x20
    const val SATIN_RED_B8202D_BLUE_8BIT = 0x2D

    const val SATIN_RED_B8202D_RED_SRGB = SATIN_RED_B8202D_RED_8BIT / 255f
    const val SATIN_RED_B8202D_GREEN_SRGB = SATIN_RED_B8202D_GREEN_8BIT / 255f
    const val SATIN_RED_B8202D_BLUE_SRGB = SATIN_RED_B8202D_BLUE_8BIT / 255f

    const val SATIN_C2050E_RED_8BIT = 194
    const val SATIN_C2050E_GREEN_8BIT = 5
    const val SATIN_C2050E_BLUE_8BIT = 14

    const val SATIN_C2050E_RED_SRGB = SATIN_C2050E_RED_8BIT / 255f
    const val SATIN_C2050E_GREEN_SRGB = SATIN_C2050E_GREEN_8BIT / 255f
    const val SATIN_C2050E_BLUE_SRGB = SATIN_C2050E_BLUE_8BIT / 255f

    const val GLOSS_BROWN_ESPRESSO_515_SRGB_HEX = 0x643229
    const val GLOSS_BROWN_ESPRESSO_515_RED_8BIT = 100
    const val GLOSS_BROWN_ESPRESSO_515_GREEN_8BIT = 50
    const val GLOSS_BROWN_ESPRESSO_515_BLUE_8BIT = 41

    const val GLOSS_BROWN_ESPRESSO_515_RED_SRGB = GLOSS_BROWN_ESPRESSO_515_RED_8BIT / 255f
    const val GLOSS_BROWN_ESPRESSO_515_GREEN_SRGB = GLOSS_BROWN_ESPRESSO_515_GREEN_8BIT / 255f
    const val GLOSS_BROWN_ESPRESSO_515_BLUE_SRGB = GLOSS_BROWN_ESPRESSO_515_BLUE_8BIT / 255f
}

/**
 * Camera-conditioned optical response used by the lipstick shader.
 *
 * These parameters deliberately do not contain a baked highlight. The shader combines them with
 * the current camera luminance, its low-frequency lighting gradient and the reconstructed lip
 * normal, so the finish remains attached to the face and responds to the captured illumination.
 */
internal data class LipstickOpticalProfile(
    val roughness: Float,
    val specularStrength: Float,
    val highlightRetention: Float,
    val microTextureRetention: Float,
    val surfaceDetailRetention: Float,
    val satinGlowStrength: Float,
    val wetInnerEdgeStrength: Float,
) {
    init {
        require(roughness in MIN_ROUGHNESS..1f)
        require(specularStrength in UNIT_RANGE)
        require(highlightRetention in UNIT_RANGE)
        require(microTextureRetention in UNIT_RANGE)
        require(surfaceDetailRetention in UNIT_RANGE)
        require(satinGlowStrength in UNIT_RANGE)
        require(wetInnerEdgeStrength in UNIT_RANGE)
    }

    private companion object {
        private const val MIN_ROUGHNESS = 0.08f
        private val UNIT_RANGE = 0f..1f
    }
}

internal object ReferenceLipstickOptics {
    val matte = LipstickOpticalProfile(
        roughness = 1f,
        specularStrength = 0f,
        highlightRetention = 0f,
        microTextureRetention = 0.82f,
        surfaceDetailRetention = 1f,
        satinGlowStrength = 0f,
        wetInnerEdgeStrength = 0f,
    )
    val satin = LipstickOpticalProfile(
        roughness = 0.74f,
        specularStrength = 0.18f,
        highlightRetention = 0.55f,
        microTextureRetention = 0.58f,
        surfaceDetailRetention = 0.72f,
        satinGlowStrength = 1f,
        wetInnerEdgeStrength = 0f,
    )
    val gloss = LipstickOpticalProfile(
        roughness = 0.12f,
        specularStrength = 0.82f,
        highlightRetention = 1f,
        microTextureRetention = 0.12f,
        surfaceDetailRetention = 0.18f,
        satinGlowStrength = 0f,
        wetInnerEdgeStrength = 0.68f,
    )
}

internal enum class LipstickPigmentPalette {
    PRODUCT_CLASSIC_RED_999,
    SATIN_RED_B8202D,
    TRACKING_MAGENTA,
}

/**
 * Parameters that alter pigment rendering without changing lip geometry or tracking coordinates.
 * Coverage is calibrated per finish: an ideal matte coat is optically opaque at the lip core,
 * while satin and gloss retain more of the native lip/camera response.
 */
internal data class LipstickRenderProfile(
    val optics: LipstickOpticalProfile,
    val pigmentPalette: LipstickPigmentPalette,
    val coverageMultiplier: Float,
    val luminancePreservation: Float,
    val minimumLuminanceGain: Float,
    val maximumLuminanceGain: Float,
) {
    init {
        require(coverageMultiplier in 0f..MAX_COVERAGE_MULTIPLIER)
        require(luminancePreservation in 0f..1f)
        require(minimumLuminanceGain in 0f..MAX_LUMINANCE_GAIN)
        require(maximumLuminanceGain in 0f..MAX_LUMINANCE_GAIN)
        require(minimumLuminanceGain <= maximumLuminanceGain)
    }

    private companion object {
        private const val MAX_COVERAGE_MULTIPLIER = 8f
        private const val MAX_LUMINANCE_GAIN = 4f
    }
}

internal object ReferenceLipstickRenderProfiles {
    val matte = productProfile(
        optics = ReferenceLipstickOptics.matte,
        coverageMultiplier = 2f,
        luminancePreservation = 0.88f,
    )
    val satin = productProfile(
        optics = ReferenceLipstickOptics.satin,
        pigmentPalette = LipstickPigmentPalette.SATIN_RED_B8202D,
        coverageMultiplier = 2f,
        luminancePreservation = 0.68f,
    )
    val gloss = productProfile(
        optics = ReferenceLipstickOptics.gloss,
        coverageMultiplier = 1.6f,
        luminancePreservation = 0.9f,
    )
    private val trackingTestOptics = LipstickOpticalProfile(
        roughness = 1f,
        specularStrength = 0f,
        highlightRetention = 0f,
        microTextureRetention = 0f,
        surfaceDetailRetention = 0f,
        satinGlowStrength = 0f,
        wetInnerEdgeStrength = 0f,
    )

    /** Flat neon pigment used only to expose small contour motion during V6 tracking tests. */
    val trackingTest = LipstickRenderProfile(
        optics = trackingTestOptics,
        pigmentPalette = LipstickPigmentPalette.TRACKING_MAGENTA,
        coverageMultiplier = 4f,
        luminancePreservation = 0f,
        minimumLuminanceGain = 1f,
        maximumLuminanceGain = 1f,
    )

    fun forFinish(finish: LipstickFinish): LipstickRenderProfile = when (finish) {
        LipstickFinish.MATTE -> matte
        LipstickFinish.SATIN -> satin
        LipstickFinish.GLOSS -> gloss
        LipstickFinish.TRACKING_TEST -> trackingTest
    }

    private fun productProfile(
        optics: LipstickOpticalProfile,
        pigmentPalette: LipstickPigmentPalette = LipstickPigmentPalette.PRODUCT_CLASSIC_RED_999,
        coverageMultiplier: Float = 1f,
        luminancePreservation: Float = 1f,
    ) = LipstickRenderProfile(
        optics = optics,
        pigmentPalette = pigmentPalette,
        coverageMultiplier = coverageMultiplier,
        luminancePreservation = luminancePreservation,
        minimumLuminanceGain = 0.55f,
        maximumLuminanceGain = 1.65f,
    )
}
