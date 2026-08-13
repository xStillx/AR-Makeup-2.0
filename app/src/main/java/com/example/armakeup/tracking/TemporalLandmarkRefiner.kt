package com.example.armakeup.tracking

import com.example.armakeup.render.VulkanTemporalTrackingResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Timestamped confidence gate between GPU optical flow and the visible landmark mesh.
 *
 * Only a contiguous chain newer than the MediaPipe anchor is composed. A bad measurement or a
 * timestamp gap makes the caller use the existing motion predictor instead of propagating drift.
 * Visible application is disabled by default while V4 is being measured as a shadow signal.
 */
internal class TemporalLandmarkRefiner(
    internal val visibleApplicationEnabled: Boolean = DEFAULT_VISIBLE_APPLICATION_ENABLED,
    private val maxHistoryNs: Long = DEFAULT_MAX_HISTORY_NS,
    private val maxGapNs: Long = DEFAULT_MAX_GAP_NS,
    private val maxRenderExtrapolationNs: Long = DEFAULT_MAX_RENDER_EXTRAPOLATION_NS,
) {
    private data class DeliveredSample(
        val result: VulkanTemporalTrackingResult,
        val deliveryElapsedRealtimeNs: Long,
    )

    internal data class SimilarityTransform(
        val scaleCos: Float = 1f,
        val scaleSin: Float = 0f,
        val translationX: Float = 0f,
        val translationY: Float = 0f,
        val confidence: Float = 1f,
    ) {
        fun mapX(x: Float, y: Float): Float = scaleCos * x - scaleSin * y + translationX

        fun mapY(x: Float, y: Float): Float = scaleSin * x + scaleCos * y + translationY

        fun then(next: SimilarityTransform): SimilarityTransform = SimilarityTransform(
            scaleCos = next.scaleCos * scaleCos - next.scaleSin * scaleSin,
            scaleSin = next.scaleSin * scaleCos + next.scaleCos * scaleSin,
            translationX = next.scaleCos * translationX -
                next.scaleSin * translationY + next.translationX,
            translationY = next.scaleSin * translationX +
                next.scaleCos * translationY + next.translationY,
            confidence = minOf(confidence, next.confidence),
        )

        fun fraction(fraction: Float): SimilarityTransform {
            val amount = fraction.coerceIn(0f, MAX_EXTRAPOLATION_FACTOR)
            val scale = hypot(scaleCos, scaleSin)
            val angle = atan2(scaleSin, scaleCos)
            val partialScale = 1f + (scale - 1f) * amount
            val partialAngle = angle * amount
            return SimilarityTransform(
                scaleCos = partialScale * cos(partialAngle),
                scaleSin = partialScale * sin(partialAngle),
                translationX = translationX * amount,
                translationY = translationY * amount,
                confidence = confidence,
            )
        }

        fun remainsBounded(): Boolean {
            val scale = hypot(scaleCos, scaleSin)
            val angle = abs(atan2(scaleSin, scaleCos))
            return scale in MIN_CUMULATIVE_SCALE..MAX_CUMULATIVE_SCALE &&
                angle <= MAX_CUMULATIVE_ROTATION &&
                hypot(translationX, translationY) <= MAX_CUMULATIVE_TRANSLATION
        }

        companion object {
            private const val MAX_EXTRAPOLATION_FACTOR = 2f
            private const val MIN_CUMULATIVE_SCALE = 0.82f
            private const val MAX_CUMULATIVE_SCALE = 1.18f
            private const val MAX_CUMULATIVE_ROTATION = 0.28f
            private const val MAX_CUMULATIVE_TRANSLATION = 0.18f
        }
    }

    private val samples = ArrayDeque<DeliveredSample>()

    init {
        require(maxHistoryNs > 0L)
        require(maxGapNs > 0L)
        require(maxRenderExtrapolationNs >= 0L)
    }

    fun offer(result: VulkanTemporalTrackingResult, deliveryElapsedRealtimeNs: Long) {
        if (!result.passesNativeContract) return
        val latest = samples.lastOrNull()?.result
        if (latest != null && result.toSensorTimestampNs <= latest.toSensorTimestampNs) {
            samples.clear()
        }
        samples.addLast(DeliveredSample(result, deliveryElapsedRealtimeNs))
        val oldestAllowed = result.toSensorTimestampNs - maxHistoryNs
        while (samples.firstOrNull()?.result?.toSensorTimestampNs?.let { it < oldestAllowed } == true) {
            samples.removeFirst()
        }
    }

    fun correctionFor(
        anchorSensorTimestampNs: Long,
        nowElapsedRealtimeNs: Long,
    ): SimilarityTransform? {
        // V4 optical flow remains a shadow signal until recorded device A/B proves that it is
        // more stable than the accepted motion-predictor baseline. Applying intermittent flow
        // results and falling back on rejected frames makes the visible mesh switch coordinate
        // sources at camera frequency, which is perceived as severe stationary jitter.
        if (!visibleApplicationEnabled) return null
        if (anchorSensorTimestampNs <= 0L || samples.isEmpty()) return null
        var composed = SimilarityTransform()
        var previousTimestampNs = anchorSensorTimestampNs
        var usedSamples = 0
        var lastDelivered: DeliveredSample? = null

        for (delivered in samples) {
            val sample = delivered.result
            if (sample.toSensorTimestampNs <= anchorSensorTimestampNs) continue
            if (sample.fromSensorTimestampNs - previousTimestampNs > maxGapNs) return null
            val fraction = if (anchorSensorTimestampNs > sample.fromSensorTimestampNs) {
                (sample.toSensorTimestampNs - anchorSensorTimestampNs).toFloat() /
                    sample.durationNs.toFloat()
            } else {
                1f
            }
            val step = sample.toTransform().fraction(fraction)
            composed = composed.then(step)
            if (!composed.remainsBounded()) return null
            previousTimestampNs = sample.toSensorTimestampNs
            usedSamples++
            lastDelivered = delivered
        }
        if (usedSamples == 0) return null

        val latest = checkNotNull(lastDelivered)
        val renderLeadNs = (nowElapsedRealtimeNs - latest.deliveryElapsedRealtimeNs)
            .coerceIn(0L, maxRenderExtrapolationNs)
        if (renderLeadNs > 0L) {
            val extrapolation = renderLeadNs.toFloat() / latest.result.durationNs.toFloat()
            composed = composed.then(latest.result.toTransform().fraction(extrapolation))
            if (!composed.remainsBounded()) return null
        }
        return composed
    }

    fun clear() {
        samples.clear()
    }

    private fun VulkanTemporalTrackingResult.toTransform(): SimilarityTransform =
        SimilarityTransform(
            scaleCos = scaleCos,
            scaleSin = scaleSin,
            translationX = translationX,
            translationY = translationY,
            confidence = confidence,
        )

    companion object {
        private const val DEFAULT_VISIBLE_APPLICATION_ENABLED = false
        private const val DEFAULT_MAX_HISTORY_NS = 280_000_000L
        private const val DEFAULT_MAX_GAP_NS = 85_000_000L
        private const val DEFAULT_MAX_RENDER_EXTRAPOLATION_NS = 42_000_000L
    }
}
