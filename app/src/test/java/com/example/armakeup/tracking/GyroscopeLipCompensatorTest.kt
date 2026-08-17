package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroscopeLipCompensatorTest {
    @Test
    fun historyIntegratesAndInterpolatesConstantAngularVelocity() {
        val history = GyroscopeRotationHistory()
        addConstantSamples(history, x = 1f, y = 2f, z = -0.5f)

        val rotation = requireNotNull(history.rotationBetween(BASE_NS + 15_000_000L, BASE_NS + 75_000_000L))

        assertEquals(0.06f, rotation.xRadians, EPSILON)
        assertEquals(0.12f, rotation.yRadians, EPSILON)
        assertEquals(-0.03f, rotation.zRadians, EPSILON)
    }

    @Test
    fun historyRejectsQueriesAcrossSensorGap() {
        val history = GyroscopeRotationHistory()
        history.addSample(BASE_NS, 1f, 0f, 0f)
        history.addSample(BASE_NS + 10_000_000L, 1f, 0f, 0f)
        history.addSample(BASE_NS + 100_000_000L, 1f, 0f, 0f)
        history.addSample(BASE_NS + 110_000_000L, 1f, 0f, 0f)

        assertEquals(null, history.rotationBetween(BASE_NS + 10_000_000L, BASE_NS + 100_000_000L))
    }

    @Test
    fun mirroredYawMovesCorrectionInMirroredDisplayDirection() {
        val history = GyroscopeRotationHistory()
        addConstantSamples(history, x = 0f, y = 1f, z = 0f)
        val compensator = GyroscopeLipCompensator(history)
        val calibration = CameraProjectionCalibration(0.7f, 0.9f)

        val mirrored = compensator.correctionFor(
            meshSensorTimestampNs = BASE_NS + 20_000_000L,
            cameraSensorTimestampNs = BASE_NS + 70_000_000L,
            cameraRotationDegrees = 270,
            mirrorHorizontal = true,
            displayRotation = 0,
            calibration = calibration,
        )
        val unmirrored = compensator.correctionFor(
            meshSensorTimestampNs = BASE_NS + 20_000_000L,
            cameraSensorTimestampNs = BASE_NS + 70_000_000L,
            cameraRotationDegrees = 270,
            mirrorHorizontal = false,
            displayRotation = 0,
            calibration = calibration,
        )

        assertTrue(mirrored.applied)
        assertTrue(mirrored.translationX > 0f)
        assertTrue(unmirrored.translationX < 0f)
        assertEquals(-mirrored.translationX, unmirrored.translationX, EPSILON)
    }

    @Test
    fun pitchAndRollApplyOneGlobalTransformWithoutChangingLocalDistance() {
        val history = GyroscopeRotationHistory()
        addConstantSamples(history, x = 1f, y = 0f, z = 0.5f)
        val correction = GyroscopeLipCompensator(history).correctionFor(
            meshSensorTimestampNs = BASE_NS + 20_000_000L,
            cameraSensorTimestampNs = BASE_NS + 70_000_000L,
            cameraRotationDegrees = 270,
            mirrorHorizontal = true,
            displayRotation = 0,
            calibration = CameraProjectionCalibration(0.7f, 0.9f),
        )
        val ax = correction.mapX(0.4f, 0.5f)
        val ay = correction.mapY(0.4f, 0.5f)
        val bx = correction.mapX(0.6f, 0.5f)
        val by = correction.mapY(0.6f, 0.5f)

        assertTrue(correction.translationY < 0f)
        val correctedDistanceSquared = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        assertEquals(0.04f, correctedDistanceSquared, EPSILON)
    }

    @Test
    fun correctionRequiresCalibrationAndCompleteTimestampCoverage() {
        val history = GyroscopeRotationHistory()
        addConstantSamples(history, x = 0f, y = 1f, z = 0f)
        val compensator = GyroscopeLipCompensator(history)

        assertFalse(
            compensator.correctionFor(
                BASE_NS,
                BASE_NS + 50_000_000L,
                270,
                true,
                0,
                null,
            ).applied,
        )
        assertFalse(
            compensator.correctionFor(
                BASE_NS - 1L,
                BASE_NS + 50_000_000L,
                270,
                true,
                0,
                CameraProjectionCalibration(0.7f, 0.9f),
            ).applied,
        )
    }

    @Test
    fun displayRotationRemapsDevicePitchIntoDisplayYaw() {
        val history = GyroscopeRotationHistory()
        addConstantSamples(history, x = 1f, y = 0f, z = 0f)
        val correction = GyroscopeLipCompensator(history).correctionFor(
            meshSensorTimestampNs = BASE_NS + 20_000_000L,
            cameraSensorTimestampNs = BASE_NS + 70_000_000L,
            cameraRotationDegrees = 0,
            mirrorHorizontal = true,
            displayRotation = 1,
            calibration = CameraProjectionCalibration(0.7f, 0.9f),
        )

        assertTrue(correction.translationX > 0f)
        assertEquals(0f, correction.translationY, EPSILON)
    }

    @Test
    fun physicalCameraCalibrationMapsFocalLengthToNormalizedAxes() {
        val calibration = requireNotNull(
            CameraProjectionCalibration.fromPhysicalSensor(
                focalLengthMillimeters = 3.2f,
                sensorWidthMillimeters = 4.8f,
                sensorHeightMillimeters = 3.6f,
            ),
        )

        assertEquals(2f / 3f, calibration.rawFocalXNormalized, EPSILON)
        assertEquals(8f / 9f, calibration.rawFocalYNormalized, EPSILON)
        assertEquals(calibration.rawFocalYNormalized, calibration.displayFocalX(270), EPSILON)
        assertEquals(calibration.rawFocalXNormalized, calibration.displayFocalY(270), EPSILON)
    }

    private fun addConstantSamples(
        history: GyroscopeRotationHistory,
        x: Float,
        y: Float,
        z: Float,
    ) {
        repeat(11) { index ->
            history.addSample(BASE_NS + index * 10_000_000L, x, y, z)
        }
    }

    companion object {
        private const val BASE_NS = 1_000_000_000L
        private const val EPSILON = 1e-4f
    }
}
