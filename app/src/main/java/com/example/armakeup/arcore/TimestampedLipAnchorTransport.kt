package com.example.armakeup.arcore

import java.util.ArrayDeque
import kotlin.math.abs

/** Transports a measured 2D lip shape by the ARCore mouth-anchor motion between camera frames. */
internal class TimestampedLipAnchorTransport(
    private val maximumHistoryNs: Long = DEFAULT_MAXIMUM_HISTORY_NS,
    private val maximumLookupDistanceNs: Long = DEFAULT_MAXIMUM_LOOKUP_DISTANCE_NS,
) {
    private val samples = ArrayDeque<Sample>()

    fun record(timestampNs: Long, x: Float, y: Float) {
        if (timestampNs <= 0L || !x.isFinite() || !y.isFinite()) return
        val latest = samples.peekLast()
        if (latest != null && timestampNs < latest.timestampNs) {
            reset()
        } else if (latest != null && timestampNs == latest.timestampNs) {
            samples.removeLast()
        }
        samples.addLast(Sample(timestampNs, x, y))
        val oldestAllowedTimestampNs = timestampNs - maximumHistoryNs
        while (samples.size > 2) {
            val oldest = samples.peekFirst() ?: break
            if (oldest.timestampNs >= oldestAllowedTimestampNs) break
            samples.removeFirst()
        }
    }

    fun correctionFor(measurementTimestampNs: Long, renderTimestampNs: Long): Correction? {
        if (measurementTimestampNs <= 0L || renderTimestampNs < measurementTimestampNs) return null
        val measured = sampleAt(measurementTimestampNs) ?: return null
        val current = sampleAt(renderTimestampNs) ?: return null
        return Correction(current.x - measured.x, current.y - measured.y)
    }

    fun reset() {
        samples.clear()
    }

    private fun sampleAt(timestampNs: Long): Sample? {
        if (samples.isEmpty()) return null
        var previous: Sample? = null
        for (sample in samples) {
            if (sample.timestampNs == timestampNs) return sample
            if (sample.timestampNs > timestampNs) {
                val before = previous
                if (before != null) {
                    val spanNs = sample.timestampNs - before.timestampNs
                    if (spanNs > 0L) {
                        val fraction = (timestampNs - before.timestampNs).toFloat() / spanNs
                        return Sample(
                            timestampNs,
                            before.x + (sample.x - before.x) * fraction,
                            before.y + (sample.y - before.y) * fraction,
                        )
                    }
                }
                return sample.takeIf {
                    abs(sample.timestampNs - timestampNs) <= maximumLookupDistanceNs
                }
            }
            previous = sample
        }
        val latest = previous ?: return null
        return latest.takeIf {
            abs(latest.timestampNs - timestampNs) <= maximumLookupDistanceNs
        }
    }

    internal data class Correction(val translationX: Float, val translationY: Float)

    private data class Sample(val timestampNs: Long, val x: Float, val y: Float)

    private companion object {
        const val DEFAULT_MAXIMUM_HISTORY_NS = 1_000_000_000L
        const val DEFAULT_MAXIMUM_LOOKUP_DISTANCE_NS = 20_000_000L
    }
}
