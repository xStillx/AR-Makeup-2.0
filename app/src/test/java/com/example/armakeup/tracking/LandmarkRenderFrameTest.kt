package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkRenderFrameTest {

    @Test
    fun oldMeasurementKeepsAnimatingAfterResultDelivery() {
        val delivered = movingFrame(measurementTimestampMs = 1_000L)
            .deliveredAt(timestampMs = 1_100L)

        val atDelivery = delivered.x(0, delivered.predictionSeconds(1_100L))
        val duringNextVsync = delivered.x(0, delivered.predictionSeconds(1_120L))

        assertEquals(0.245f, atDelivery, EPSILON)
        assertEquals(0.265f, duringNextVsync, EPSILON)
        assertTrue(delivered.shouldAnimate(1_120L))
    }

    @Test
    fun renderExtrapolationStopsAfterBoundedWindow() {
        val delivered = movingFrame(measurementTimestampMs = 1_000L)
            .deliveredAt(timestampMs = 1_100L)

        val atWindowEnd = delivered.x(0, delivered.predictionSeconds(1_142L))
        val longAfterWindow = delivered.x(0, delivered.predictionSeconds(2_000L))

        assertEquals(atWindowEnd, longAfterWindow, EPSILON)
        assertFalse(delivered.shouldAnimate(1_142L))
    }

    @Test
    fun undeliveredFrameKeepsOriginalPredictionLimit() {
        val frame = movingFrame(measurementTimestampMs = 1_000L)

        val atLimit = frame.x(0, frame.predictionSeconds(1_045L))
        val afterLimit = frame.x(0, frame.predictionSeconds(2_000L))

        assertEquals(atLimit, afterLimit, EPSILON)
        assertFalse(frame.shouldAnimate(1_045L))
    }

    @Test
    fun newResultStartsAtPreviouslyRenderedPosition() {
        val previous = stationaryFrame(positionX = 0.2f, measurementTimestampMs = 1_000L)
            .deliveredAt(timestampMs = 1_000L)
        val corrected = stationaryFrame(positionX = 0.3f, measurementTimestampMs = 1_033L)
            .deliveredAt(timestampMs = 1_033L)
            .smoothCorrectionFrom(previous, timestampMs = 1_033L)

        val atDelivery = corrected.x(0, corrected.predictionSeconds(1_033L))

        assertEquals(0.2f, atDelivery, EPSILON)
    }

    @Test
    fun renderCorrectionUsesSmoothstepAndFinishesQuickly() {
        val previous = stationaryFrame(positionX = 0.2f, measurementTimestampMs = 1_000L)
            .deliveredAt(timestampMs = 1_000L)
        val corrected = stationaryFrame(positionX = 0.3f, measurementTimestampMs = 1_033L)
            .deliveredAt(timestampMs = 1_033L)
            .smoothCorrectionFrom(previous, timestampMs = 1_033L)

        val halfway = corrected.x(0, corrected.predictionSeconds(1_041L))
        val completed = corrected.x(0, corrected.predictionSeconds(1_049L))

        assertEquals(0.25f, halfway, EPSILON)
        assertEquals(0.3f, completed, EPSILON)
    }

    @Test
    fun faceReacquisitionIsNotBlendedAcrossLargeJump() {
        val previous = stationaryFrame(positionX = 0.1f, measurementTimestampMs = 1_000L)
            .deliveredAt(timestampMs = 1_000L)
        val reacquired = stationaryFrame(positionX = 0.4f, measurementTimestampMs = 1_033L)
            .deliveredAt(timestampMs = 1_033L)
            .smoothCorrectionFrom(previous, timestampMs = 1_033L)

        val atDelivery = reacquired.x(0, reacquired.predictionSeconds(1_033L))

        assertEquals(0.4f, atDelivery, EPSILON)
    }

    private fun movingFrame(measurementTimestampMs: Long): LandmarkRenderFrame =
        LandmarkRenderFrame(
            positions = floatArrayOf(0.2f, 0.4f, 0f),
            velocities = floatArrayOf(1f, 0f, 0f),
            measurementTimestampMs = measurementTimestampMs,
            predictedOnly = false,
            renderLeadMs = 0L,
            maxPredictionMs = 45L,
        )

    private fun stationaryFrame(
        positionX: Float,
        measurementTimestampMs: Long,
    ): LandmarkRenderFrame =
        LandmarkRenderFrame(
            positions = floatArrayOf(positionX, 0.4f, 0f),
            velocities = floatArrayOf(0f, 0f, 0f),
            measurementTimestampMs = measurementTimestampMs,
            predictedOnly = false,
            renderLeadMs = 0L,
            maxPredictionMs = 45L,
        )

    companion object {
        private const val EPSILON = 0.0001f
    }
}
