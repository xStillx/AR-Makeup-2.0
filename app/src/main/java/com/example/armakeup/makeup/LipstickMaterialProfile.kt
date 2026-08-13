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

/** User-visible optical finish. Pigment coverage remains identical between finishes. */
internal enum class LipstickFinish {
    MATTE,
    SATIN,
    GLOSS,
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
    val wetInnerEdgeStrength: Float,
) {
    init {
        require(roughness in MIN_ROUGHNESS..1f)
        require(specularStrength in UNIT_RANGE)
        require(highlightRetention in UNIT_RANGE)
        require(microTextureRetention in UNIT_RANGE)
        require(wetInnerEdgeStrength in UNIT_RANGE)
    }

    private companion object {
        private const val MIN_ROUGHNESS = 0.08f
        private val UNIT_RANGE = 0f..1f
    }
}

internal object ReferenceLipstickOptics {
    val matte = LipstickOpticalProfile(
        roughness = 0.82f,
        specularStrength = 0.07f,
        highlightRetention = 0.24f,
        microTextureRetention = 0.94f,
        wetInnerEdgeStrength = 0.03f,
    )
    val satin = LipstickOpticalProfile(
        roughness = 0.48f,
        specularStrength = 0.20f,
        highlightRetention = 0.68f,
        microTextureRetention = 0.86f,
        wetInnerEdgeStrength = 0.11f,
    )
    val gloss = LipstickOpticalProfile(
        roughness = 0.20f,
        specularStrength = 0.46f,
        highlightRetention = 1f,
        microTextureRetention = 0.72f,
        wetInnerEdgeStrength = 0.32f,
    )

    fun forFinish(finish: LipstickFinish): LipstickOpticalProfile = when (finish) {
        LipstickFinish.MATTE -> matte
        LipstickFinish.SATIN -> satin
        LipstickFinish.GLOSS -> gloss
    }
}
