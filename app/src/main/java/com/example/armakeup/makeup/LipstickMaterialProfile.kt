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
