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

    @Test
    fun smallStationaryJitterIsAttenuated() {
        val predictor = predictor(renderLeadMs = 0L)
        predictor.update(point(0.500f, 0.4f), timestampMs = 1_000L)

        val frame = predictor.update(point(0.501f, 0.4f), timestampMs = 1_040L)
        val filteredX = frame.x(0, frame.predictionSeconds(1_040L))

        assertTrue(filteredX > 0.500f)
        assertTrue(filteredX < 0.501f)
    }

    @Test
    fun velocityDeadZonePreventsStationaryNoiseFromDrifting() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(point(0.5000f, 0.4f), timestampMs = 1_000L)
        val frame = predictor.update(point(0.5001f, 0.4f), timestampMs = 1_040L)

        val atMeasurement = frame.x(0, frame.predictionSeconds(1_040L))
        val afterPrediction = frame.x(0, frame.predictionSeconds(1_090L))

        assertEquals(atMeasurement, afterPrediction, EPSILON)
    }

    @Test
    fun coherentFaceMotionRaisesPositionCutoffImmediately() {
        val predictor = predictor(renderLeadMs = 0L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)

        val frame = predictor.update(face(translationX = 0.02f), timestampMs = 1_040L)
        val predictionSeconds = frame.predictionSeconds(1_040L)
        val filteredTranslation = frame.x(0, predictionSeconds) - FACE_X_COORDINATES[0]

        assertTrue(filteredTranslation > 0.014f)
        assertTrue(filteredTranslation < 0.02f)
    }

    @Test
    fun coherentFaceVelocityIsAvailableFromFirstMovingFrame() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        val frame = predictor.update(face(translationX = 0.02f), timestampMs = 1_040L)

        val atMeasurement = frame.x(0, frame.predictionSeconds(1_040L))
        val atRender = frame.x(0, frame.predictionSeconds(1_080L))

        assertTrue(atRender - atMeasurement > 0.012f)
    }

    @Test
    fun coherentVelocityDecaysQuicklyWhenCameraStops() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        predictor.update(face(translationX = 0.02f), timestampMs = 1_040L)
        val stopped = predictor.update(face(translationX = 0.02f), timestampMs = 1_080L)

        val atStop = stopped.x(0, stopped.predictionSeconds(1_080L))
        val afterPrediction = stopped.x(0, stopped.predictionSeconds(1_130L))

        assertTrue(afterPrediction - atStop < 0.004f)
    }

    @Test
    fun slowCoherentMotionRecoversMostOfItsDelayByRenderTime() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        val frame = predictor.update(face(translationX = 0.004f), timestampMs = 1_040L)

        val renderedTranslation = frame.x(0, frame.predictionSeconds(1_080L)) -
            FACE_X_COORDINATES[0]

        assertTrue(renderedTranslation > 0.005f)
    }

    private fun predictor(
        renderLeadMs: Long = 4L,
        maxPredictionMs: Long = 45L,
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

    private fun face(translationX: Float): FloatArray =
        FloatArray(FACE_X_COORDINATES.size * 3).also { coordinates ->
            FACE_X_COORDINATES.forEachIndexed { index, x ->
                coordinates[index * 3] = x + translationX
                coordinates[index * 3 + 1] = 0.3f + index * 0.1f
            }
        }

    companion object {
        private const val EPSILON = 0.0001f
        private val FACE_X_COORDINATES = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
    }
}
