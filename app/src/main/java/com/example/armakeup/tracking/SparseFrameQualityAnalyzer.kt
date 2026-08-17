package com.example.armakeup.tracking

import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.sqrt

/** Sparse, allocation-free luminance/contrast measurement over the packed RGBA analysis frame. */
internal class SparseFrameQualityAnalyzer {
    private var previousRowLuma = FloatArray(0)

    fun analyze(buffer: ByteBuffer, width: Int, height: Int): ImageSignalQuality {
        if (
            width <= 0 || height <= 0 ||
            buffer.capacity() < width * height * RGBA_BYTES_PER_PIXEL
        ) {
            return ImageSignalQuality.UNKNOWN
        }
        val columns = min(TARGET_SAMPLE_COLUMNS, width)
        val rows = min(TARGET_SAMPLE_ROWS, height)
        if (previousRowLuma.size != columns) previousRowLuma = FloatArray(columns)

        var lumaSum = 0.0
        var lumaSquaredSum = 0.0
        var gradientSum = 0.0
        var sampleCount = 0
        var gradientCount = 0
        repeat(rows) { row ->
            val y = sampleCoordinate(row, rows, height)
            var previousLuma = Float.NaN
            repeat(columns) { column ->
                val x = sampleCoordinate(column, columns, width)
                val byteIndex = (y * width + x) * RGBA_BYTES_PER_PIXEL
                val red = buffer.get(byteIndex).toInt() and BYTE_MASK
                val green = buffer.get(byteIndex + 1).toInt() and BYTE_MASK
                val blue = buffer.get(byteIndex + 2).toInt() and BYTE_MASK
                val luma = (
                    LUMA_RED_WEIGHT * red +
                        LUMA_GREEN_WEIGHT * green +
                        LUMA_BLUE_WEIGHT * blue
                ).div(LUMA_NORMALIZATION).toFloat()
                lumaSum += luma
                lumaSquaredSum += luma * luma
                sampleCount++
                if (previousLuma.isFinite()) {
                    gradientSum += kotlin.math.abs(luma - previousLuma)
                    gradientCount++
                }
                if (row > 0) {
                    gradientSum += kotlin.math.abs(luma - previousRowLuma[column])
                    gradientCount++
                }
                previousRowLuma[column] = luma
                previousLuma = luma
            }
        }
        if (sampleCount == 0) return ImageSignalQuality.UNKNOWN

        val meanLuma = (lumaSum / sampleCount).toFloat()
        val variance = (lumaSquaredSum / sampleCount - meanLuma * meanLuma)
            .coerceAtLeast(0.0)
        return ImageSignalQuality(
            meanLuma = meanLuma,
            lumaStandardDeviation = sqrt(variance).toFloat(),
            meanGradient = if (gradientCount == 0) {
                0f
            } else {
                (gradientSum / gradientCount).toFloat()
            },
        )
    }

    private fun sampleCoordinate(sample: Int, sampleCount: Int, dimension: Int): Int =
        if (sampleCount <= 1) 0 else sample * (dimension - 1) / (sampleCount - 1)

    companion object {
        private const val TARGET_SAMPLE_COLUMNS = 32
        private const val TARGET_SAMPLE_ROWS = 24
        private const val RGBA_BYTES_PER_PIXEL = 4
        private const val BYTE_MASK = 0xff
        private const val LUMA_RED_WEIGHT = 0.2126
        private const val LUMA_GREEN_WEIGHT = 0.7152
        private const val LUMA_BLUE_WEIGHT = 0.0722
        private const val LUMA_NORMALIZATION = 255.0
    }
}

internal data class ImageSignalQuality(
    val meanLuma: Float,
    val lumaStandardDeviation: Float,
    val meanGradient: Float,
) {
    companion object {
        val UNKNOWN = ImageSignalQuality(Float.NaN, Float.NaN, Float.NaN)
    }
}
