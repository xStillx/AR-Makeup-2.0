package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkMotionPredictorTest {

    @Test
    fun firstMeasurementStartsWithoutInventedMotion() {
        val predictor = predictor(renderLeadMs = 8L)

        val frame = predictor.update(point(0.2f, 0.4f), timestampMs = 1_000L)
        val predictionSeconds = frame.predictionSeconds(1_000L)

        assertEquals(0.2f, frame.x(0, predictionSeconds), EPSILON)
        assertEquals(0.4f, frame.y(0, predictionSeconds), EPSILON)
        assertFalse(frame.predictedOnly)
    }

    @Test
    fun constantMotionIsExtrapolatedTowardRenderTimestamp() {
        val predictor = predictor(renderLeadMs = 0L)
        predictor.update(point(0.20f, 0.4f), timestampMs = 1_000L)
        val frame = predictor.update(point(0.24f, 0.4f), timestampMs = 1_040L)

        val atMeasurement = frame.x(0, frame.predictionSeconds(1_040L))
        val atRender = frame.x(0, frame.predictionSeconds(1_080L))

        assertTrue(atRender > atMeasurement)
    }

    @Test
    fun extrapolationStopsAtConfiguredHorizon() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(point(0.20f, 0.4f), timestampMs = 1_000L)
        val frame = predictor.update(point(0.24f, 0.4f), timestampMs = 1_040L)

        val atCap = frame.x(0, frame.predictionSeconds(1_090L))
        val longAfterCap = frame.x(0, frame.predictionSeconds(2_000L))

        assertEquals(atCap, longAfterCap, EPSILON)
        assertFalse(frame.shouldAnimate(1_090L))
    }

    @Test
    fun shortDetectionDropoutKeepsPredictedFrame() {
        val predictor = predictor(maxPredictionMs = 50L, holdAfterLossMs = 100L)
        predictor.update(point(0.2f, 0.4f), timestampMs = 1_000L)

        val heldFrame = predictor.predictWithoutMeasurement(1_099L)

        assertNotNull(heldFrame)
        assertTrue(heldFrame!!.predictedOnly)
        assertNull(predictor.predictWithoutMeasurement(1_101L))
    }

    @Test
    fun implausibleJumpResetsVelocityInsteadOfOvershooting() {
        val predictor = predictor(renderLeadMs = 0L, maxCentroidJump = 0.2f)
        predictor.update(point(0.10f, 0.4f), timestampMs = 1_000L)
        predictor.update(point(0.12f, 0.4f), timestampMs = 1_040L)

        val reacquired = predictor.update(point(0.90f, 0.4f), timestampMs = 1_080L)
        val later = reacquired.x(0, reacquired.predictionSeconds(1_120L))

        assertEquals(0.90f, later, EPSILON)
    }

    private fun predictor(
        renderLeadMs: Long = 8L,
        maxPredictionMs: Long = 65L,
        holdAfterLossMs: Long = 120L,
        maxCentroidJump: Float = 0.25f,
    ) = LandmarkMotionPredictor(
        renderLeadMs = renderLeadMs,
        maxPredictionMs = maxPredictionMs,
        holdAfterLossMs = holdAfterLossMs,
        maxCentroidJump = maxCentroidJump,
    )

    private fun point(x: Float, y: Float, z: Float = 0f): FloatArray =
        floatArrayOf(x, y, z)

    companion object {
        private const val EPSILON = 0.0001f
    }
}
