package com.example.armakeup.arcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreVulkanUvTransformTest {

    @Test
    fun `writes identity mapping from top-left texture corners`() {
        val matrix = FloatArray(16)

        ArCoreVulkanUvTransform.writeFromTextureCorners(
            textureCorners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
            destination = matrix,
        )

        assertUv(matrix, x = 0.25f, y = 0.75f, expectedU = 0.25f, expectedV = 0.75f)
    }

    @Test
    fun `preserves ARCore affine rotation and crop mapping`() {
        val matrix = FloatArray(16)

        ArCoreVulkanUvTransform.writeFromTextureCorners(
            textureCorners = floatArrayOf(0.8f, 0.1f, 0.8f, 0.9f, 0.2f, 0.1f),
            destination = matrix,
        )

        assertUv(matrix, x = 0f, y = 0f, expectedU = 0.8f, expectedV = 0.1f)
        assertUv(matrix, x = 1f, y = 0f, expectedU = 0.8f, expectedV = 0.9f)
        assertUv(matrix, x = 0f, y = 1f, expectedU = 0.2f, expectedV = 0.1f)
        assertUv(matrix, x = 0.5f, y = 0.5f, expectedU = 0.5f, expectedV = 0.5f)
    }

    private fun assertUv(
        matrix: FloatArray,
        x: Float,
        y: Float,
        expectedU: Float,
        expectedV: Float,
    ) {
        val u = matrix[0] * x + matrix[4] * y + matrix[12]
        val v = matrix[1] * x + matrix[5] * y + matrix[13]
        assertEquals(expectedU, u, 1e-6f)
        assertEquals(expectedV, v, 1e-6f)
    }
}
