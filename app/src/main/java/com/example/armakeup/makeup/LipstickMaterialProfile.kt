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
 * Reference-calibrated rose-brick matte material.
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
    val wetInnerEdgeStrength: Float,
) {
    init {
        require(roughness in MIN_ROUGHNESS..1f)
        require(specularStrength in UNIT_RANGE)
        require(highlightRetention in UNIT_RANGE)
        require(microTextureRetention in UNIT_RANGE)
        require(surfaceDetailRetention in UNIT_RANGE)
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
        wetInnerEdgeStrength = 0f,
    )
    val satin = LipstickOpticalProfile(
        roughness = 0.68f,
        specularStrength = 0.18f,
        highlightRetention = 0.62f,
        microTextureRetention = 0.48f,
        surfaceDetailRetention = 1f,
        wetInnerEdgeStrength = 0.06f,
    )
    val gloss = LipstickOpticalProfile(
        roughness = 0.12f,
        specularStrength = 0.82f,
        highlightRetention = 1f,
        microTextureRetention = 0.12f,
        surfaceDetailRetention = 0.18f,
        wetInnerEdgeStrength = 0.68f,
    )
}

internal enum class LipstickPigmentPalette {
    PRODUCT_ROSE,
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
) {
    init {
        require(coverageMultiplier in 0f..MAX_COVERAGE_MULTIPLIER)
        require(luminancePreservation in 0f..1f)
    }

    private companion object {
        private const val MAX_COVERAGE_MULTIPLIER = 8f
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
        coverageMultiplier = 1.55f,
        luminancePreservation = 0.92f,
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
        wetInnerEdgeStrength = 0f,
    )

    /** Flat neon pigment used only to expose small contour motion during V6 tracking tests. */
    val trackingTest = LipstickRenderProfile(
        optics = trackingTestOptics,
        pigmentPalette = LipstickPigmentPalette.TRACKING_MAGENTA,
        coverageMultiplier = 4f,
        luminancePreservation = 0f,
    )

    fun forFinish(finish: LipstickFinish): LipstickRenderProfile = when (finish) {
        LipstickFinish.MATTE -> matte
        LipstickFinish.SATIN -> satin
        LipstickFinish.GLOSS -> gloss
        LipstickFinish.TRACKING_TEST -> trackingTest
    }

    private fun productProfile(
        optics: LipstickOpticalProfile,
        coverageMultiplier: Float = 1f,
        luminancePreservation: Float = 1f,
    ) = LipstickRenderProfile(
        optics = optics,
        pigmentPalette = LipstickPigmentPalette.PRODUCT_ROSE,
        coverageMultiplier = coverageMultiplier,
        luminancePreservation = luminancePreservation,
    )
}
