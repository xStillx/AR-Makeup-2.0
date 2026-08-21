package com.example.armakeup.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLocalAffineTransformTest {

    @Test
    fun `recovers affine mapping with rotation reflection and offset`() {
        val source = floatArrayOf(
            0.1f, 0.2f,
            0.8f, 0.2f,
            0.2f, 0.9f,
            0.7f, 0.8f,
            0.5f, 0.4f,
        )
        val target = FloatArray(source.size)
        repeat(source.size / 2) { pointIndex ->
            val index = pointIndex * 2
            val x = source[index]
            val y = source[index + 1]
            target[index] = 0.7f * x - 0.2f * y + 0.15f
            target[index + 1] = -0.1f * x - 0.9f * y + 0.95f
        }

        val transform = requireNotNull(FaceLocalAffineTransform.estimate(source, target))

        assertEquals(0.7f, transform.xFromX, 1e-5f)
        assertEquals(-0.2f, transform.xFromY, 1e-5f)
        assertEquals(0.15f, transform.xOffset, 1e-5f)
        assertEquals(-0.1f, transform.yFromX, 1e-5f)
        assertEquals(-0.9f, transform.yFromY, 1e-5f)
        assertEquals(0.95f, transform.yOffset, 1e-5f)
        assertTrue(transform.normalizedRmsResidual < 1e-5f)
    }

    @Test
    fun `rejects collinear source anchors`() {
        val source = floatArrayOf(0f, 0f, 0.5f, 0.5f, 1f, 1f)
        val target = floatArrayOf(0f, 1f, 0.5f, 0.5f, 1f, 0f)

        assertNull(FaceLocalAffineTransform.estimate(source, target))
    }
}
