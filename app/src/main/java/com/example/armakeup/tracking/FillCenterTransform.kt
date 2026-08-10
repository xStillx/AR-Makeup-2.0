package com.example.armakeup.tracking

import kotlin.math.max

data class FillCenterTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun mapX(
        normalizedX: Float,
        sourceWidth: Int,
        mirrorHorizontal: Boolean = false,
    ): Float {
        val displayX = if (mirrorHorizontal) 1f - normalizedX else normalizedX
        return displayX * sourceWidth * scale + offsetX
    }

    fun mapY(normalizedY: Float, sourceHeight: Int): Float =
        normalizedY * sourceHeight * scale + offsetY

    companion object {
        fun calculate(
            viewWidth: Int,
            viewHeight: Int,
            sourceWidth: Int,
            sourceHeight: Int,
        ): FillCenterTransform {
            require(viewWidth > 0 && viewHeight > 0 && sourceWidth > 0 && sourceHeight > 0)
            val scale = max(
                viewWidth.toFloat() / sourceWidth,
                viewHeight.toFloat() / sourceHeight,
            )
            return FillCenterTransform(
                scale = scale,
                offsetX = (viewWidth - sourceWidth * scale) / 2f,
                offsetY = (viewHeight - sourceHeight * scale) / 2f,
            )
        }
    }
}
