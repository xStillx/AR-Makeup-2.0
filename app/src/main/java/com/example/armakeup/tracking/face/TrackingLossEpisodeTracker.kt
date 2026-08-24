package com.example.armakeup.tracking.face

/** Accumulates exact consecutive-frame tracking-loss episodes without changing visibility policy. */
internal class TrackingLossEpisodeTracker {
    var consecutiveLossFrames: Int = 0
        private set
    var totalLossFrames: Long = 0L
        private set
    var maximumLossFrames: Int = 0
        private set
    var maximumLossDurationNs: Long = 0L
        private set
    var lastCompletedEpisode: TrackingLossEpisode? = null
        private set

    private var episodeStartTimestampNs = 0L

    fun record(timestampNs: Long, tracking: Boolean): TrackingLossEpisode? {
        require(timestampNs >= 0L)
        if (!tracking) {
            if (consecutiveLossFrames == 0) episodeStartTimestampNs = timestampNs
            consecutiveLossFrames++
            totalLossFrames++
            maximumLossFrames = maxOf(maximumLossFrames, consecutiveLossFrames)
            maximumLossDurationNs = maxOf(
                maximumLossDurationNs,
                (timestampNs - episodeStartTimestampNs).coerceAtLeast(0L),
            )
            return null
        }
        if (consecutiveLossFrames == 0) return null
        val completed = TrackingLossEpisode(
            frameCount = consecutiveLossFrames,
            durationNs = (timestampNs - episodeStartTimestampNs).coerceAtLeast(0L),
        )
        maximumLossDurationNs = maxOf(maximumLossDurationNs, completed.durationNs)
        lastCompletedEpisode = completed
        consecutiveLossFrames = 0
        episodeStartTimestampNs = 0L
        return completed
    }

    fun reset() {
        consecutiveLossFrames = 0
        totalLossFrames = 0L
        maximumLossFrames = 0
        maximumLossDurationNs = 0L
        lastCompletedEpisode = null
        episodeStartTimestampNs = 0L
    }
}

internal data class TrackingLossEpisode(
    val frameCount: Int,
    val durationNs: Long,
) {
    init {
        require(frameCount > 0)
        require(durationNs >= 0L)
    }
}
