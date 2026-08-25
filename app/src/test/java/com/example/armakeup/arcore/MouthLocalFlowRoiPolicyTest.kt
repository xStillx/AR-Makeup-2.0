package com.example.armakeup.arcore

import com.example.armakeup.tracking.face.FaceRegion
import com.example.armakeup.tracking.face.FaceRegionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouthLocalFlowRoiPolicyTest {
    @Test
    fun `expands mouth bounds while remaining in display coordinates`() {
        val outer = FaceRegionGeometry.of(
            FaceRegion.LIPS_OUTER,
            floatArrayOf(
                0.30f, 0.50f,
                0.50f, 0.45f,
                0.70f, 0.50f,
                0.50f, 0.55f,
            ),
        )

        val roi = MouthLocalFlowRoiPolicy.from(outer)

        assertTrue(roi.isValid)
        assertEquals(0.20f, roi.left, EPSILON)
        assertEquals(0.375f, roi.top, EPSILON)
        assertEquals(0.80f, roi.right, EPSILON)
        assertEquals(0.625f, roi.bottom, EPSILON)
    }

    @Test
    fun `clamps expanded roi at display boundary`() {
        val outer = FaceRegionGeometry.of(
            FaceRegion.LIPS_OUTER,
            floatArrayOf(
                0.01f, 0.01f,
                0.21f, 0.01f,
                0.21f, 0.06f,
                0.01f, 0.06f,
            ),
        )

        val roi = MouthLocalFlowRoiPolicy.from(outer)

        assertEquals(0f, roi.left, EPSILON)
        assertEquals(0f, roi.top, EPSILON)
        assertTrue(roi.isValid)
    }

    @Test
    fun `rejects missing or degenerate mouth geometry`() {
        assertFalse(MouthLocalFlowRoiPolicy.from(null).isValid)
        val point = FaceRegionGeometry.of(FaceRegion.LIPS_OUTER, floatArrayOf(0.5f, 0.5f))
        assertFalse(MouthLocalFlowRoiPolicy.from(point).isValid)
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
