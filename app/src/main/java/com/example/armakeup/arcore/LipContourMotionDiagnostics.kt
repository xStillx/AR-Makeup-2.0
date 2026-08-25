package com.example.armakeup.arcore

import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Measures MediaPipe lip-local motion before and after contour stabilization.
 *
 * Coordinates are expressed in an eye-anchored similarity frame, so phone/head translation,
 * in-plane rotation and scale do not inflate local expression/noise measurements.
 */
internal class LipContourMotionDiagnostics(
    private val logIntervalMs: Long = DEFAULT_LOG_INTERVAL_MS,
    private val logger: (String) -> Unit = { message -> Log.i(TAG, message) },
) {
    private var previousRaw = FloatArray(0)
    private var previousStabilized = FloatArray(0)
    private var previousTimestampMs = NO_TIMESTAMP
    private var windowStartedAtMs = NO_TIMESTAMP
    private var window = WindowAccumulator()

    init {
        require(logIntervalMs > 0L)
    }

    fun record(
        anchors: FloatArray,
        rawOuter: FloatArray,
        rawInner: FloatArray,
        stabilizedOuter: FloatArray,
        stabilizedInner: FloatArray,
        timestampMs: Long,
    ): LipContourMotionSample? {
        val raw = normalize(anchors, rawOuter, rawInner) ?: return resetAndReturnNull()
        val stabilized = normalize(anchors, stabilizedOuter, stabilizedInner)
            ?: return resetAndReturnNull()
        val elapsedMs = timestampMs - previousTimestampMs
        if (
            previousTimestampMs == NO_TIMESTAMP ||
            previousRaw.size != raw.packed.size ||
            previousStabilized.size != stabilized.packed.size ||
            elapsedMs <= 0L ||
            elapsedMs > MAXIMUM_SAMPLE_GAP_MS
        ) {
            initialize(raw.packed, stabilized.packed, timestampMs)
            return null
        }

        val sample = LipContourMotionSample(
            elapsedMs = elapsedMs,
            rawLocalStepRms = rmsDifference(raw.packed, previousRaw),
            stabilizedLocalStepRms = rmsDifference(stabilized.packed, previousStabilized),
            filterCorrectionRms = rmsDifference(raw.packed, stabilized.packed),
            rawUpperBandThickness = raw.upperBandThickness,
            stabilizedUpperBandThickness = stabilized.upperBandThickness,
            rawLowerBandThickness = raw.lowerBandThickness,
            stabilizedLowerBandThickness = stabilized.lowerBandThickness,
            rawMouthAperture = raw.mouthAperture,
            stabilizedMouthAperture = stabilized.mouthAperture,
        )
        raw.packed.copyInto(previousRaw)
        stabilized.packed.copyInto(previousStabilized)
        previousTimestampMs = timestampMs
        if (windowStartedAtMs == NO_TIMESTAMP) windowStartedAtMs = timestampMs
        window.add(sample)
        if (timestampMs - windowStartedAtMs >= logIntervalMs) {
            logger(window.toLogLine())
            window = WindowAccumulator()
            windowStartedAtMs = timestampMs
        }
        return sample
    }

    fun reset() {
        previousRaw = FloatArray(0)
        previousStabilized = FloatArray(0)
        previousTimestampMs = NO_TIMESTAMP
        windowStartedAtMs = NO_TIMESTAMP
        window = WindowAccumulator()
    }

    private fun resetAndReturnNull(): LipContourMotionSample? {
        reset()
        return null
    }

    private fun initialize(raw: FloatArray, stabilized: FloatArray, timestampMs: Long) {
        previousRaw = raw.copyOf()
        previousStabilized = stabilized.copyOf()
        previousTimestampMs = timestampMs
        windowStartedAtMs = timestampMs
        window = WindowAccumulator()
    }

    private fun normalize(
        anchors: FloatArray,
        outer: FloatArray,
        inner: FloatArray,
    ): NormalizedLipShape? {
        if (
            anchors.size < MINIMUM_ANCHOR_COMPONENTS ||
            outer.size != inner.size ||
            outer.size < MINIMUM_CONTOUR_COMPONENTS ||
            outer.size % POINT_COMPONENTS != 0 ||
            !anchors.all(Float::isFinite) ||
            !outer.all(Float::isFinite) ||
            !inner.all(Float::isFinite)
        ) {
            return null
        }
        val leftEyeX = (anchors[0] + anchors[2]) * 0.5f
        val leftEyeY = (anchors[1] + anchors[3]) * 0.5f
        val rightEyeX = (anchors[4] + anchors[6]) * 0.5f
        val rightEyeY = (anchors[5] + anchors[7]) * 0.5f
        val axisX = rightEyeX - leftEyeX
        val axisY = rightEyeY - leftEyeY
        val eyeDistance = hypot(axisX, axisY)
        if (!eyeDistance.isFinite() || eyeDistance <= MINIMUM_EYE_DISTANCE) return null
        val centerX = (leftEyeX + rightEyeX) * 0.5f
        val centerY = (leftEyeY + rightEyeY) * 0.5f
        val unitX = axisX / eyeDistance
        val unitY = axisY / eyeDistance
        val packed = FloatArray(outer.size + inner.size)
        writeNormalized(outer, packed, 0, centerX, centerY, unitX, unitY, eyeDistance)
        writeNormalized(
            inner,
            packed,
            outer.size,
            centerX,
            centerY,
            unitX,
            unitY,
            eyeDistance,
        )
        val pointCount = outer.size / POINT_COMPONENTS
        val oppositeCorner = pointCount / 2
        var lowerThickness = 0f
        var upperThickness = 0f
        var lowerCount = 0
        var upperCount = 0
        for (pointIndex in 1 until oppositeCorner) {
            lowerThickness += normalizedBandWidth(packed, outer.size, pointIndex)
            lowerCount++
        }
        for (pointIndex in oppositeCorner + 1 until pointCount) {
            upperThickness += normalizedBandWidth(packed, outer.size, pointIndex)
            upperCount++
        }
        val lowerCenter = pointCount / 4
        val upperCenter = pointCount * 3 / 4
        val innerOffset = outer.size
        val mouthAperture = abs(
            packed[innerOffset + lowerCenter * POINT_COMPONENTS + 1] -
                packed[innerOffset + upperCenter * POINT_COMPONENTS + 1],
        )
        return NormalizedLipShape(
            packed = packed,
            upperBandThickness = upperThickness / upperCount.coerceAtLeast(1),
            lowerBandThickness = lowerThickness / lowerCount.coerceAtLeast(1),
            mouthAperture = mouthAperture,
        )
    }

    private fun writeNormalized(
        source: FloatArray,
        destination: FloatArray,
        destinationOffset: Int,
        centerX: Float,
        centerY: Float,
        unitX: Float,
        unitY: Float,
        scale: Float,
    ) {
        var coordinate = 0
        while (coordinate < source.size) {
            val deltaX = source[coordinate] - centerX
            val deltaY = source[coordinate + 1] - centerY
            destination[destinationOffset + coordinate] =
                (deltaX * unitX + deltaY * unitY) / scale
            destination[destinationOffset + coordinate + 1] =
                (-deltaX * unitY + deltaY * unitX) / scale
            coordinate += POINT_COMPONENTS
        }
    }

    private fun normalizedBandWidth(
        packed: FloatArray,
        innerOffset: Int,
        pointIndex: Int,
    ): Float {
        val outerCoordinate = pointIndex * POINT_COMPONENTS
        val innerCoordinate = innerOffset + outerCoordinate
        return hypot(
            packed[outerCoordinate] - packed[innerCoordinate],
            packed[outerCoordinate + 1] - packed[innerCoordinate + 1],
        )
    }

    private fun rmsDifference(first: FloatArray, second: FloatArray): Float {
        var squaredSum = 0f
        var pointCount = 0
        var coordinate = 0
        while (coordinate < first.size) {
            val deltaX = first[coordinate] - second[coordinate]
            val deltaY = first[coordinate + 1] - second[coordinate + 1]
            squaredSum += deltaX * deltaX + deltaY * deltaY
            pointCount++
            coordinate += POINT_COMPONENTS
        }
        return sqrt(squaredSum / pointCount.coerceAtLeast(1))
    }

    private data class NormalizedLipShape(
        val packed: FloatArray,
        val upperBandThickness: Float,
        val lowerBandThickness: Float,
        val mouthAperture: Float,
    )

    private class WindowAccumulator {
        private var sampleCount = 0
        private var elapsedMsTotal = 0L
        private var rawStepTotal = 0f
        private var stabilizedStepTotal = 0f
        private var correctionTotal = 0f
        private var peakRawStep = 0f
        private var peakCorrection = 0f
        private var latest: LipContourMotionSample? = null

        fun add(sample: LipContourMotionSample) {
            sampleCount++
            elapsedMsTotal += sample.elapsedMs
            rawStepTotal += sample.rawLocalStepRms
            stabilizedStepTotal += sample.stabilizedLocalStepRms
            correctionTotal += sample.filterCorrectionRms
            peakRawStep = maxOf(peakRawStep, sample.rawLocalStepRms)
            peakCorrection = maxOf(peakCorrection, sample.filterCorrectionRms)
            latest = sample
        }

        fun toLogLine(): String {
            val count = sampleCount.coerceAtLeast(1)
            val current = latest
            return String.format(
                Locale.US,
                "FF5 lipDynamics n=%d dt=%.1fms localStep raw/stable=%.2f/%.2fpermille " +
                    "peakRaw=%.2f correction avg/peak=%.2f/%.2fpermille " +
                    "aperture raw/stable=%.2f/%.2fpermille " +
                    "band rawU/rawL=%.2f/%.2f stableU/stableL=%.2f/%.2fpermille",
                sampleCount,
                elapsedMsTotal.toFloat() / count,
                rawStepTotal / count * PERMILLE,
                stabilizedStepTotal / count * PERMILLE,
                peakRawStep * PERMILLE,
                correctionTotal / count * PERMILLE,
                peakCorrection * PERMILLE,
                (current?.rawMouthAperture ?: 0f) * PERMILLE,
                (current?.stabilizedMouthAperture ?: 0f) * PERMILLE,
                (current?.rawUpperBandThickness ?: 0f) * PERMILLE,
                (current?.rawLowerBandThickness ?: 0f) * PERMILLE,
                (current?.stabilizedUpperBandThickness ?: 0f) * PERMILLE,
                (current?.stabilizedLowerBandThickness ?: 0f) * PERMILLE,
            )
        }
    }

    private companion object {
        const val TAG = "ARMakeupLipDynamics"
        const val POINT_COMPONENTS = 2
        const val MINIMUM_ANCHOR_COMPONENTS = 8
        const val MINIMUM_CONTOUR_COMPONENTS = 8
        const val MINIMUM_EYE_DISTANCE = 1e-5f
        const val MAXIMUM_SAMPLE_GAP_MS = 220L
        const val DEFAULT_LOG_INTERVAL_MS = 1_000L
        const val PERMILLE = 1_000f
        const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}

internal data class LipContourMotionSample(
    val elapsedMs: Long,
    val rawLocalStepRms: Float,
    val stabilizedLocalStepRms: Float,
    val filterCorrectionRms: Float,
    val rawUpperBandThickness: Float,
    val stabilizedUpperBandThickness: Float,
    val rawLowerBandThickness: Float,
    val stabilizedLowerBandThickness: Float,
    val rawMouthAperture: Float,
    val stabilizedMouthAperture: Float,
)
