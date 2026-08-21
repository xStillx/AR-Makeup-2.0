package com.example.armakeup.render

import com.example.armakeup.tracking.GyroscopeLipCompensator
import com.example.armakeup.tracking.LandmarkRenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVulkanRenderMotionCompensatorTest {
    private val compensator = NativeVulkanRenderMotionCompensator()

    @Test
    fun coherentHeadMotionReceivesBoundedResidualLead() {
        val prediction = compensator.predictionFor(
            landmarks = movingFrame(speed = 0.5f, coverage = 1f),
            renderTimestampMs = 1_000L,
            baseGyroscopeCorrection = GyroscopeLipCompensator.Correction.NONE,
        )

        assertEquals(0.032f, prediction.additionalSeconds, EPSILON)
        assertEquals(prediction.baseSeconds + 0.032f, prediction.totalSeconds, EPSILON)
        assertEquals(prediction.baseSeconds + 0.032f, prediction.cameraMotionSeconds, EPSILON)
    }

    @Test
    fun slowOrUnconfirmedMotionStaysOnTrackerPrediction() {
        val slow = compensator.predictionFor(
            movingFrame(speed = 0.05f, coverage = 1f),
            1_000L,
            GyroscopeLipCompensator.Correction.NONE,
        )
        val unconfirmed = compensator.predictionFor(
            movingFrame(speed = 0.5f, coverage = 0f),
            1_000L,
            GyroscopeLipCompensator.Correction.NONE,
        )

        assertEquals(0f, slow.additionalSeconds, 0f)
        assertEquals(0f, unconfirmed.additionalSeconds, 0f)
    }

    @Test
    fun deviceRotationDisablesResidualLeadToAvoidDoubleCompensation() {
        val prediction = compensator.predictionFor(
            landmarks = movingFrame(speed = 0.5f, coverage = 1f),
            renderTimestampMs = 1_000L,
            baseGyroscopeCorrection = correction(rotationY = 0.02f),
        )

        assertEquals(0f, prediction.additionalSeconds, 0f)
    }

    @Test
    fun transitionBetweenHeadAndDeviceMotionIsContinuous() {
        val lowRotation = compensator.predictionFor(
            movingFrame(speed = 0.25f, coverage = 0.8f),
            1_000L,
            correction(rotationY = 0.003f),
        )
        val higherRotation = compensator.predictionFor(
            movingFrame(speed = 0.25f, coverage = 0.8f),
            1_000L,
            correction(rotationY = 0.006f),
        )

        assertTrue(lowRotation.additionalSeconds > higherRotation.additionalSeconds)
        assertTrue(higherRotation.additionalSeconds > 0f)
        assertTrue(lowRotation.additionalSeconds < 0.032f)
    }

    private fun movingFrame(speed: Float, coverage: Float): LandmarkRenderFrame =
        LandmarkRenderFrame(
            positions = floatArrayOf(0.2f, 0.4f, 0f),
            velocities = floatArrayOf(speed, 0f, 0f),
            measurementTimestampMs = 1_000L,
            predictedOnly = false,
            renderLeadMs = 0L,
            maxPredictionMs = 45L,
            globalPredictionCoverage = coverage,
        )

    private fun correction(rotationY: Float) = GyroscopeLipCompensator.Correction(
        applied = true,
        intervalMs = 20f,
        rotationX = 0f,
        rotationY = rotationY,
        rotationZ = 0f,
        translationX = 0f,
        translationY = 0f,
        rollRadians = 0f,
    )

    companion object {
        private const val EPSILON = 1e-5f
    }
}
