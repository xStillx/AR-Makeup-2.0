package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTimeLandmarkPredictorTest {
    @Test
    fun keepsLatestMeasurementAsUnfilteredBaseAndPredictsToCameraTimestamp() {
        val predictor = DisplayTimeLandmarkPredictor()
        predictor.update(face(), timestampMs = 1_000L)
        val moved = translated(face(), x = 0.033f)

        val frame = predictor.update(moved, timestampMs = 1_033L)
        val predictionSeconds = frame.predictionSecondsForCameraFrame(
            measurementSensorTimestampNs = 1_033_000_000L,
            cameraSensorTimestampNs = 1_050_000_000L,
        )

        assertEquals(moved[0], frame.copyBasePositions()[0], EPSILON)
        assertEquals(0.017f, predictionSeconds, EPSILON)
        assertEquals(moved[0] + 0.017f, frame.x(0, predictionSeconds), 0.002f)
    }

    @Test
    fun cameraHorizonNeverRunsBackwardAndIsBounded() {
        val predictor = DisplayTimeLandmarkPredictor()
        val frame = predictor.update(face(), timestampMs = 1_000L)

        assertEquals(
            0f,
            frame.predictionSecondsForCameraFrame(1_000_000_000L, 999_000_000L),
            EPSILON,
        )
        assertEquals(
            0.05f,
            frame.predictionSecondsForCameraFrame(1_000_000_000L, 2_000_000_000L),
            EPSILON,
        )
    }

    @Test
    fun stopClearsVelocityImmediatelyInsteadOfOvershooting() {
        val predictor = DisplayTimeLandmarkPredictor()
        predictor.update(face(), timestampMs = 1_000L)
        val moved = translated(face(), x = 0.033f)
        predictor.update(moved, timestampMs = 1_033L)

        val stopped = predictor.update(moved, timestampMs = 1_066L)
        val predictionSeconds = stopped.predictionSecondsForCameraFrame(
            measurementSensorTimestampNs = 1_066_000_000L,
            cameraSensorTimestampNs = 1_099_000_000L,
        )

        assertEquals(moved[0], stopped.x(0, predictionSeconds), EPSILON)
        assertTrue(stopped.copyVelocities().all { it == 0f })
    }

    private fun face(): FloatArray = FloatArray(LANDMARK_COUNT * 3).also { coordinates ->
        repeat(LANDMARK_COUNT) { landmarkIndex ->
            val index = landmarkIndex * 3
            coordinates[index] = 0.25f + (landmarkIndex % 17) * 0.025f
            coordinates[index + 1] = 0.20f + (landmarkIndex % 19) * 0.025f
            coordinates[index + 2] = -0.05f
        }
    }

    private fun translated(source: FloatArray, x: Float): FloatArray = source.copyOf().also {
        var index = 0
        while (index < it.size) {
            it[index] += x
            index += 3
        }
    }

    companion object {
        private const val LANDMARK_COUNT = 478
        private const val EPSILON = 0.0001f
    }
}
