package com.example.armakeup.tracking.face

/**
 * Converts measured short tracking-loss bursts into continuous opacity without predicting pose.
 *
 * Geometry is retained at full opacity for the existing 100 ms grace period, then fades to zero
 * by 350 ms (the measured 303 ms pitch-down maximum plus one camera interval). Reacquisition fades
 * back from the last loss opacity instead of producing an immediate hide/show edge.
 */
internal class TrackingVisibilityController(
    private val fullOpacityHoldNs: Long = DEFAULT_FULL_OPACITY_HOLD_NS,
    private val fadeOutEndNs: Long = DEFAULT_FADE_OUT_END_NS,
    private val fadeInDurationNs: Long = DEFAULT_FADE_IN_DURATION_NS,
) {
    private var hasTrackedGeometry = false
    private var lossStartTimestampNs = NO_TIMESTAMP
    private var fadeInStartTimestampNs = NO_TIMESTAMP
    private var fadeInStartOpacity = 1f

    init {
        require(fullOpacityHoldNs >= 0L)
        require(fadeOutEndNs > fullOpacityHoldNs)
        require(fadeInDurationNs > 0L)
    }

    fun update(timestampNs: Long, tracking: Boolean): TrackingVisibilityDecision {
        require(timestampNs >= 0L)
        return if (tracking) trackedDecision(timestampNs) else lossDecision(timestampNs)
    }

    fun reset() {
        hasTrackedGeometry = false
        lossStartTimestampNs = NO_TIMESTAMP
        fadeInStartTimestampNs = NO_TIMESTAMP
        fadeInStartOpacity = 1f
    }

    private fun trackedDecision(timestampNs: Long): TrackingVisibilityDecision {
        if (!hasTrackedGeometry) {
            hasTrackedGeometry = true
            return TrackingVisibilityDecision(useCurrentGeometry = true, retainLastGeometry = false, opacity = 1f)
        }
        if (lossStartTimestampNs != NO_TIMESTAMP) {
            val lossDurationNs = (timestampNs - lossStartTimestampNs).coerceAtLeast(0L)
            fadeInStartOpacity = lossOpacity(lossDurationNs)
            fadeInStartTimestampNs = if (fadeInStartOpacity < 1f) timestampNs else NO_TIMESTAMP
            lossStartTimestampNs = NO_TIMESTAMP
        }
        val opacity = if (fadeInStartTimestampNs == NO_TIMESTAMP) {
            1f
        } else {
            val progress = ((timestampNs - fadeInStartTimestampNs).toFloat() / fadeInDurationNs)
                .coerceIn(0f, 1f)
            (fadeInStartOpacity + (1f - fadeInStartOpacity) * progress).also {
                if (progress >= 1f) fadeInStartTimestampNs = NO_TIMESTAMP
            }
        }
        return TrackingVisibilityDecision(
            useCurrentGeometry = true,
            retainLastGeometry = false,
            opacity = opacity,
        )
    }

    private fun lossDecision(timestampNs: Long): TrackingVisibilityDecision {
        fadeInStartTimestampNs = NO_TIMESTAMP
        if (!hasTrackedGeometry) return TrackingVisibilityDecision.HIDDEN
        if (lossStartTimestampNs == NO_TIMESTAMP) lossStartTimestampNs = timestampNs
        val opacity = lossOpacity((timestampNs - lossStartTimestampNs).coerceAtLeast(0L))
        return TrackingVisibilityDecision(
            useCurrentGeometry = false,
            retainLastGeometry = opacity > 0f,
            opacity = opacity,
        )
    }

    private fun lossOpacity(durationNs: Long): Float = when {
        durationNs <= fullOpacityHoldNs -> 1f
        durationNs >= fadeOutEndNs -> 0f
        else -> 1f - (durationNs - fullOpacityHoldNs).toFloat() /
            (fadeOutEndNs - fullOpacityHoldNs).toFloat()
    }

    private companion object {
        const val NO_TIMESTAMP = -1L
        const val DEFAULT_FULL_OPACITY_HOLD_NS = 100_000_000L
        const val DEFAULT_FADE_OUT_END_NS = 350_000_000L
        const val DEFAULT_FADE_IN_DURATION_NS = 120_000_000L
    }
}

internal data class TrackingVisibilityDecision(
    val useCurrentGeometry: Boolean,
    val retainLastGeometry: Boolean,
    val opacity: Float,
) {
    init {
        require(opacity in 0f..1f)
        require(useCurrentGeometry || retainLastGeometry || opacity == 0f)
    }

    companion object {
        val HIDDEN = TrackingVisibilityDecision(
            useCurrentGeometry = false,
            retainLastGeometry = false,
            opacity = 0f,
        )
    }
}
