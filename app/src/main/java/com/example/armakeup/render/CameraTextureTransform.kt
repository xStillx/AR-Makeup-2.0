package com.example.armakeup.render

/**
 * Maps between Filament texture coordinates and the raw CameraX preview buffer.
 *
 * CameraX and MediaPipe use a top-left image origin, while Filament UVs and the imported external
 * texture use a bottom-left origin. CameraX reports its crop in raw buffer coordinates and a
 * clockwise rotation from the buffer into display orientation. Mirroring is applied after
 * rotation for the front camera. Both origin conversions must surround that inverse transform.
 */
internal object CameraTextureTransform {

    data class CropRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        init {
            require(left >= 0 && top >= 0)
            require(width > 0 && height > 0)
        }
    }

    data class Point(val x: Float, val y: Float)

    fun mapDisplayToBuffer(
        displayX: Float,
        displayY: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        crop: CropRegion,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
    ): Point {
        require(bufferWidth > 0 && bufferHeight > 0)
        require(crop.right <= bufferWidth && crop.bottom <= bufferHeight)

        val rotation = normalizeRotation(rotationDegrees)
        val rotatedX = if (mirrorHorizontal) 1f - displayX else displayX
        val rotatedY = displayY
        val cropX: Float
        val cropY: Float
        when (rotation) {
            0 -> {
                cropX = rotatedX
                cropY = rotatedY
            }
            90 -> {
                cropX = rotatedY
                cropY = 1f - rotatedX
            }
            180 -> {
                cropX = 1f - rotatedX
                cropY = 1f - rotatedY
            }
            270 -> {
                cropX = 1f - rotatedY
                cropY = rotatedX
            }
            else -> error("Unsupported rotation: $rotation")
        }

        return Point(
            x = (crop.left + cropX * crop.width) / bufferWidth,
            y = (crop.top + cropY * crop.height) / bufferHeight,
        )
    }

    fun mapFilamentUvToTexture(
        filamentU: Float,
        filamentV: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        crop: CropRegion,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        invertDisplayHorizontally: Boolean,
        invertDisplayVertically: Boolean,
    ): Point {
        val displayX = if (invertDisplayHorizontally) 1f - filamentU else filamentU
        val displayY = if (invertDisplayVertically) filamentV else 1f - filamentV
        val bufferPoint = mapDisplayToBuffer(
            displayX = displayX,
            displayY = displayY,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            crop = crop,
            rotationDegrees = rotationDegrees,
            mirrorHorizontal = mirrorHorizontal,
        )
        return Point(
            x = bufferPoint.x,
            y = 1f - bufferPoint.y,
        )
    }

    /** Column-major affine matrix for `matrix * vec4(filamentUv, 0, 1)`. */
    fun matrix(
        bufferWidth: Int,
        bufferHeight: Int,
        crop: CropRegion,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        invertDisplayHorizontally: Boolean,
        invertDisplayVertically: Boolean,
    ): FloatArray {
        val origin = mapFilamentUvToTexture(
            0f,
            0f,
            bufferWidth,
            bufferHeight,
            crop,
            rotationDegrees,
            mirrorHorizontal,
            invertDisplayHorizontally,
            invertDisplayVertically,
        )
        val horizontal = mapFilamentUvToTexture(
            1f,
            0f,
            bufferWidth,
            bufferHeight,
            crop,
            rotationDegrees,
            mirrorHorizontal,
            invertDisplayHorizontally,
            invertDisplayVertically,
        )
        val vertical = mapFilamentUvToTexture(
            0f,
            1f,
            bufferWidth,
            bufferHeight,
            crop,
            rotationDegrees,
            mirrorHorizontal,
            invertDisplayHorizontally,
            invertDisplayVertically,
        )
        return floatArrayOf(
            horizontal.x - origin.x,
            horizontal.y - origin.y,
            0f,
            0f,
            vertical.x - origin.x,
            vertical.y - origin.y,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
            origin.x,
            origin.y,
            0f,
            1f,
        )
    }

    private fun normalizeRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION
        require(normalized % QUARTER_ROTATION == 0) {
            "Rotation must be a multiple of 90 degrees"
        }
        return normalized
    }

    private const val QUARTER_ROTATION = 90
    private const val FULL_ROTATION = 360
}
