package com.example.armakeup.render

import kotlin.math.PI
import kotlin.math.hypot

/**
 * Stabilizes lip-local shape while preserving the current global face motion.
 *
 * A similarity transform fitted on rigid eye/nose/cheek anchors transports the previously
 * displayed contour into the current face pose first. Only the remaining lip-local deformation
 * is low-pass filtered, so reducing contour shimmer does not add another global tracking lag.
 */
internal class FaceAnchoredLipContourStabilizer(
    private val localCutoffHz: Float = DEFAULT_LOCAL_CUTOFF_HZ,
    private val maximumFrameGapMs: Long = DEFAULT_MAXIMUM_FRAME_GAP_MS,
) {
    private var previousAnchors = FloatArray(0)
    private var stabilizedOuter = FloatArray(0)
    private var stabilizedInner = FloatArray(0)
    private var lastTimestampMs = NO_TIMESTAMP

    init {
        require(localCutoffHz > 0f)
        require(maximumFrameGapMs > 0L)
    }

    fun stabilize(
        anchors: FloatArray,
        outerContour: FloatArray,
        innerContour: FloatArray,
        timestampMs: Long,
    ) {
        if (!isValid(anchors, minimumPointCount = 3) ||
            !isValid(outerContour, minimumPointCount = 3) ||
            !isValid(innerContour, minimumPointCount = 3)
        ) {
            reset()
            return
        }
        val elapsedMs = timestampMs - lastTimestampMs
        if (
            elapsedMs == 0L &&
            previousAnchors.size == anchors.size &&
            stabilizedOuter.size == outerContour.size &&
            stabilizedInner.size == innerContour.size
        ) {
            // A 30 FPS retained camera buffer is normally presented twice on a 60 Hz display.
            // Keep geometry bit-identical while that camera image is unchanged.
            stabilizedOuter.copyInto(outerContour)
            stabilizedInner.copyInto(innerContour)
            return
        }
        if (
            previousAnchors.size != anchors.size ||
            stabilizedOuter.size != outerContour.size ||
            stabilizedInner.size != innerContour.size ||
            lastTimestampMs == NO_TIMESTAMP ||
            elapsedMs < 0L ||
            elapsedMs > maximumFrameGapMs
        ) {
            initialize(anchors, outerContour, innerContour, timestampMs)
            return
        }

        val transform = SimilarityTransform.fit(previousAnchors, anchors)
        if (transform == null) {
            initialize(anchors, outerContour, innerContour, timestampMs)
            return
        }
        val elapsedSeconds = elapsedMs / MILLIS_PER_SECOND
        val alpha = smoothingAlpha(localCutoffHz, elapsedSeconds)
        transportAndBlend(transform, stabilizedOuter, outerContour, alpha)
        transportAndBlend(transform, stabilizedInner, innerContour, alpha)
        outerContour.indices.forEach { outerContour[it] = stabilizedOuter[it] }
        innerContour.indices.forEach { innerContour[it] = stabilizedInner[it] }
        anchors.copyInto(previousAnchors)
        lastTimestampMs = timestampMs
    }

    fun reset() {
        previousAnchors = FloatArray(0)
        stabilizedOuter = FloatArray(0)
        stabilizedInner = FloatArray(0)
        lastTimestampMs = NO_TIMESTAMP
    }

    private fun initialize(
        anchors: FloatArray,
        outerContour: FloatArray,
        innerContour: FloatArray,
        timestampMs: Long,
    ) {
        previousAnchors = anchors.copyOf()
        stabilizedOuter = outerContour.copyOf()
        stabilizedInner = innerContour.copyOf()
        lastTimestampMs = timestampMs
    }

    private fun transportAndBlend(
        transform: SimilarityTransform,
        stabilized: FloatArray,
        measured: FloatArray,
        alpha: Float,
    ) {
        var index = 0
        while (index < stabilized.size) {
            val transportedX = transform.mapX(stabilized[index], stabilized[index + 1])
            val transportedY = transform.mapY(stabilized[index], stabilized[index + 1])
            stabilized[index] = transportedX + alpha * (measured[index] - transportedX)
            stabilized[index + 1] = transportedY + alpha * (measured[index + 1] - transportedY)
            index += POINT_COMPONENTS
        }
    }

    private fun smoothingAlpha(cutoffHz: Float, elapsedSeconds: Float): Float {
        val timeConstant = 1f / (TWO_PI * cutoffHz)
        return 1f / (1f + timeConstant / elapsedSeconds)
    }

    private fun isValid(points: FloatArray, minimumPointCount: Int): Boolean =
        points.size >= minimumPointCount * POINT_COMPONENTS &&
            points.size % POINT_COMPONENTS == 0 &&
            points.all(Float::isFinite)

    private data class SimilarityTransform(
        val sourceCenterX: Float,
        val sourceCenterY: Float,
        val targetCenterX: Float,
        val targetCenterY: Float,
        val real: Float,
        val imaginary: Float,
    ) {
        fun mapX(x: Float, y: Float): Float = targetCenterX +
            real * (x - sourceCenterX) - imaginary * (y - sourceCenterY)

        fun mapY(x: Float, y: Float): Float = targetCenterY +
            imaginary * (x - sourceCenterX) + real * (y - sourceCenterY)

        companion object {
            fun fit(source: FloatArray, target: FloatArray): SimilarityTransform? {
                if (source.size != target.size || source.size < MINIMUM_ANCHOR_COORDINATES) {
                    return null
                }
                var sourceCenterX = 0f
                var sourceCenterY = 0f
                var targetCenterX = 0f
                var targetCenterY = 0f
                var index = 0
                while (index < source.size) {
                    sourceCenterX += source[index]
                    sourceCenterY += source[index + 1]
                    targetCenterX += target[index]
                    targetCenterY += target[index + 1]
                    index += POINT_COMPONENTS
                }
                val pointCount = source.size / POINT_COMPONENTS
                sourceCenterX /= pointCount
                sourceCenterY /= pointCount
                targetCenterX /= pointCount
                targetCenterY /= pointCount

                var denominator = 0f
                var realNumerator = 0f
                var imaginaryNumerator = 0f
                index = 0
                while (index < source.size) {
                    val sourceX = source[index] - sourceCenterX
                    val sourceY = source[index + 1] - sourceCenterY
                    val targetX = target[index] - targetCenterX
                    val targetY = target[index + 1] - targetCenterY
                    denominator += sourceX * sourceX + sourceY * sourceY
                    realNumerator += sourceX * targetX + sourceY * targetY
                    imaginaryNumerator += sourceX * targetY - sourceY * targetX
                    index += POINT_COMPONENTS
                }
                if (!denominator.isFinite() || denominator <= MINIMUM_ANCHOR_VARIANCE) return null
                val real = realNumerator / denominator
                val imaginary = imaginaryNumerator / denominator
                val scale = hypot(real, imaginary)
                val centerShift = hypot(
                    targetCenterX - sourceCenterX,
                    targetCenterY - sourceCenterY,
                )
                if (
                    !scale.isFinite() || scale !in MINIMUM_FRAME_SCALE..MAXIMUM_FRAME_SCALE ||
                    !centerShift.isFinite() || centerShift > MAXIMUM_CENTER_SHIFT
                ) {
                    return null
                }
                return SimilarityTransform(
                    sourceCenterX,
                    sourceCenterY,
                    targetCenterX,
                    targetCenterY,
                    real,
                    imaginary,
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_LOCAL_CUTOFF_HZ = 8f
        private const val DEFAULT_MAXIMUM_FRAME_GAP_MS = 100L
        private const val MINIMUM_FRAME_SCALE = 0.80f
        private const val MAXIMUM_FRAME_SCALE = 1.25f
        private const val MAXIMUM_CENTER_SHIFT = 0.20f
        private const val MINIMUM_ANCHOR_VARIANCE = 1e-7f
        private const val MINIMUM_ANCHOR_COORDINATES = 6
        private const val POINT_COMPONENTS = 2
        private const val MILLIS_PER_SECOND = 1_000f
        private const val TWO_PI = (2.0 * PI).toFloat()
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
