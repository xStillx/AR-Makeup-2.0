package com.example.armakeup.tracking

/**
 * Maps a CameraX sensor timestamp to the uptime clock used by rendering and MediaPipe.
 *
 * On cameras whose sensor timestamp uses the realtime clock, subtracting it from the current
 * realtime value exposes the frame age before ImageAnalysis received it. Invalid or incompatible
 * camera timebases safely fall back to the current uptime timestamp.
 */
internal class CameraFrameTimestampResolver(
    private val maxPlausibleFrameAgeMs: Long = DEFAULT_MAX_PLAUSIBLE_FRAME_AGE_MS,
) {
    private var lastTimestampMs = NO_TIMESTAMP

    init {
        require(maxPlausibleFrameAgeMs >= 0L)
    }

    fun resolve(
        cameraTimestampNs: Long,
        nowElapsedRealtimeNs: Long,
        nowUptimeMs: Long,
    ): Long {
        val frameAgeNs = nowElapsedRealtimeNs - cameraTimestampNs
        val maxPlausibleFrameAgeNs = maxPlausibleFrameAgeMs * NANOS_PER_MILLI
        val frameAgeMs = if (
            cameraTimestampNs > 0L && frameAgeNs in 0L..maxPlausibleFrameAgeNs
        ) {
            frameAgeNs / NANOS_PER_MILLI
        } else {
            0L
        }
        val sourceTimestampMs = (nowUptimeMs - frameAgeMs).coerceAtLeast(0L)
        val resolvedTimestampMs = if (lastTimestampMs == NO_TIMESTAMP) {
            sourceTimestampMs
        } else {
            maxOf(sourceTimestampMs, lastTimestampMs + 1L)
        }
        lastTimestampMs = resolvedTimestampMs
        return resolvedTimestampMs
    }

    companion object {
        private const val DEFAULT_MAX_PLAUSIBLE_FRAME_AGE_MS = 500L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
