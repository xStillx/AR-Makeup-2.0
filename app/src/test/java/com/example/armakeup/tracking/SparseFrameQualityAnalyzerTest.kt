package com.example.armakeup.tracking

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparseFrameQualityAnalyzerTest {
    @Test
    fun uniformFrameHasExpectedLumaAndNoGradient() {
        val frame = rgbaFrame(width = 8, height = 8) { _, _ -> intArrayOf(64, 64, 64) }

        val quality = SparseFrameQualityAnalyzer().analyze(frame, width = 8, height = 8)

        assertEquals(64f / 255f, quality.meanLuma, EPSILON)
        assertEquals(0f, quality.lumaStandardDeviation, EPSILON)
        assertEquals(0f, quality.meanGradient, EPSILON)
    }

    @Test
    fun highContrastEdgeRaisesContrastAndGradientSignals() {
        val frame = rgbaFrame(width = 8, height = 8) { x, _ ->
            if (x < 4) intArrayOf(0, 0, 0) else intArrayOf(255, 255, 255)
        }

        val quality = SparseFrameQualityAnalyzer().analyze(frame, width = 8, height = 8)

        assertEquals(0.5f, quality.meanLuma, EPSILON)
        assertTrue(quality.lumaStandardDeviation > 0.45f)
        assertTrue(quality.meanGradient > 0f)
    }

    private fun rgbaFrame(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> IntArray,
    ): ByteBuffer = ByteBuffer.allocate(width * height * 4).also { buffer ->
        repeat(height) { y ->
            repeat(width) { x ->
                val color = colorAt(x, y)
                buffer.put(color[0].toByte())
                buffer.put(color[1].toByte())
                buffer.put(color[2].toByte())
                buffer.put(0xff.toByte())
            }
        }
        buffer.flip()
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
