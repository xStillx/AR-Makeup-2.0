package com.example.armakeup.arcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadDownLipVisibilityGateTest {
    private val hide = Math.toRadians(24.0).toFloat()
    private val restore = Math.toRadians(18.0).toFloat()

    @Test
    fun sustainedStrongDownPitchHidesAfterConfirmation() {
        val gate = HeadDownLipVisibilityGate(hide, restore, confirmationFrames = 3)

        assertTrue(gate.update(Math.toRadians(35.0).toFloat()))
        assertTrue(gate.update(Math.toRadians(35.0).toFloat()))
        assertFalse(gate.update(Math.toRadians(35.0).toFloat()))
    }

    @Test
    fun hysteresisKeepsMaskHiddenNearHideThreshold() {
        val gate = HeadDownLipVisibilityGate(hide, restore, confirmationFrames = 1)
        assertFalse(gate.update(Math.toRadians(35.0).toFloat()))

        assertFalse(gate.update(Math.toRadians(28.0).toFloat()))
        assertFalse(gate.update(Math.toRadians(25.0).toFloat()))
    }

    @Test
    fun sustainedRecoveryRestoresVisibility() {
        val gate = HeadDownLipVisibilityGate(hide, restore, confirmationFrames = 2)
        gate.update(Math.toRadians(35.0).toFloat())
        assertFalse(gate.update(Math.toRadians(35.0).toFloat()))

        assertFalse(gate.update(Math.toRadians(15.0).toFloat()))
        assertTrue(gate.update(Math.toRadians(15.0).toFloat()))
    }

    @Test
    fun resetStartsNewFaceVisible() {
        val gate = HeadDownLipVisibilityGate(hide, restore, confirmationFrames = 1)
        assertFalse(gate.update(Math.toRadians(40.0).toFloat()))

        gate.reset()

        assertTrue(gate.update(0f))
    }
}
