package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingVisibilityControllerTest {

    @Test
    fun `retains then fades measured short loss instead of blinking`() {
        val controller = TrackingVisibilityController(
            fullOpacityHoldNs = 100L,
            fadeOutEndNs = 350L,
            fadeInDurationNs = 120L,
        )
        controller.update(1_000L, tracking = true)

        assertEquals(1f, controller.update(1_010L, tracking = false).opacity, 0f)
        assertEquals(1f, controller.update(1_110L, tracking = false).opacity, 0f)
        val fading = controller.update(1_260L, tracking = false)
        assertTrue(fading.retainLastGeometry)
        assertEquals(0.4f, fading.opacity, 1e-5f)

        val reacquired = controller.update(1_313L, tracking = true)
        assertTrue(reacquired.useCurrentGeometry)
        assertEquals(0.188f, reacquired.opacity, 1e-3f)
        assertEquals(1f, controller.update(1_433L, tracking = true).opacity, 1e-5f)
    }

    @Test
    fun `hides long loss and fades in after reacquisition`() {
        val controller = TrackingVisibilityController(
            fullOpacityHoldNs = 100L,
            fadeOutEndNs = 350L,
            fadeInDurationNs = 120L,
        )
        controller.update(1_000L, tracking = true)
        controller.update(1_010L, tracking = false)

        val hidden = controller.update(1_400L, tracking = false)
        assertFalse(hidden.retainLastGeometry)
        assertEquals(0f, hidden.opacity, 0f)
        assertEquals(0f, controller.update(1_410L, tracking = true).opacity, 0f)
        assertEquals(0.5f, controller.update(1_470L, tracking = true).opacity, 1e-5f)
        assertEquals(1f, controller.update(1_530L, tracking = true).opacity, 1e-5f)
    }

    @Test
    fun `stays hidden before first tracked geometry`() {
        val decision = TrackingVisibilityController().update(1_000L, tracking = false)

        assertFalse(decision.useCurrentGeometry)
        assertFalse(decision.retainLastGeometry)
        assertEquals(0f, decision.opacity, 0f)
    }
}
