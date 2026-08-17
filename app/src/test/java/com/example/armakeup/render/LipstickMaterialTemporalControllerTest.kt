package com.example.armakeup.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LipstickMaterialTemporalControllerTest {
    @Test
    fun stationaryGeometryKeepsFullCameraDetail() {
        val controller = LipstickMaterialTemporalController()
        val points = lipPoints()
        var state = controller.update(1_000L, points, points, 1080, 2400, 40f)
        repeat(20) { index ->
            state = controller.update(
                1_016L + index * 16L,
                points,
                points,
                1080,
                2400,
                40f,
            )
        }

        assertEquals(0f, state.motionSpeedShortEdgesPerSecond, EPSILON)
        assertEquals(1f, state.cameraDetailCoherence, EPSILON)
    }

    @Test
    fun fastMotionWithTimestampMismatchSuppressesOnlyCameraDetail() {
        val controller = LipstickMaterialTemporalController()
        var timestampMs = 1_000L
        var translation = 0f
        var points = lipPoints(translation)
        controller.update(timestampMs, points, points, 1080, 2400, 40f)

        var state = LipstickMaterialTemporalController.State.STATIONARY
        repeat(20) {
            timestampMs += 16L
            translation += 0.015f
            points = lipPoints(translation)
            state = controller.update(timestampMs, points, points, 1080, 2400, 40f)
        }

        assertTrue(state.motionSpeedShortEdgesPerSecond > 0.7f)
        assertTrue(state.estimatedSpatialMismatch > 0.018f)
        assertTrue(state.cameraDetailCoherence < 0.30f)
        assertTrue(state.cameraDetailCoherence >= 0.18f)
    }

    @Test
    fun timestampAlignedCameraDoesNotSuppressDetailDuringMotion() {
        val controller = LipstickMaterialTemporalController()
        var timestampMs = 1_000L
        var translation = 0f
        controller.update(timestampMs, lipPoints(), lipPoints(), 1080, 2400, 0f)

        var state = LipstickMaterialTemporalController.State.STATIONARY
        repeat(20) {
            timestampMs += 16L
            translation += 0.015f
            val points = lipPoints(translation)
            state = controller.update(timestampMs, points, points, 1080, 2400, 0f)
        }

        assertTrue(state.motionSpeedShortEdgesPerSecond > 0.7f)
        assertEquals(0f, state.estimatedSpatialMismatch, EPSILON)
        assertEquals(1f, state.cameraDetailCoherence, EPSILON)
    }

    @Test
    fun detailSuppressionAndRecoveryAreSmooth() {
        val controller = LipstickMaterialTemporalController()
        var timestampMs = 1_000L
        var translation = 0f
        controller.update(timestampMs, lipPoints(), lipPoints(), 1080, 2400, 50f)

        timestampMs += 16L
        translation += 0.02f
        var points = lipPoints(translation)
        var state = controller.update(timestampMs, points, points, 1080, 2400, 50f)
        assertTrue(state.cameraDetailCoherence > 0.75f)

        repeat(20) {
            timestampMs += 16L
            translation += 0.02f
            points = lipPoints(translation)
            state = controller.update(timestampMs, points, points, 1080, 2400, 50f)
        }
        val suppressed = state.cameraDetailCoherence
        assertTrue(suppressed < 0.30f)

        repeat(80) {
            timestampMs += 16L
            state = controller.update(timestampMs, points, points, 1080, 2400, 50f)
        }
        assertTrue(state.cameraDetailCoherence > suppressed)
        assertTrue(state.cameraDetailCoherence > 0.90f)
        assertTrue(state.cameraDetailCoherence <= 1f)
    }

    @Test
    fun discontinuityResetsMaterialStateInsteadOfCarryingStaleMotion() {
        val controller = LipstickMaterialTemporalController()
        val points = lipPoints()
        controller.update(1_000L, points, points, 1080, 2400, 50f)
        controller.update(1_016L, lipPoints(0.03f), lipPoints(0.03f), 1080, 2400, 50f)

        val reset = controller.update(1_500L, points, points, 1080, 2400, 50f)

        assertEquals(0f, reset.motionSpeedShortEdgesPerSecond, EPSILON)
        assertEquals(1f, reset.cameraDetailCoherence, EPSILON)
    }

    private fun lipPoints(translationX: Float = 0f): FloatArray = floatArrayOf(
        0.42f + translationX, 0.50f,
        0.48f + translationX, 0.47f,
        0.56f + translationX, 0.50f,
        0.48f + translationX, 0.53f,
    )

    private companion object {
        private const val EPSILON = 1e-5f
    }
}
