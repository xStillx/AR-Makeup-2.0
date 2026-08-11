package com.example.armakeup.render

import com.example.armakeup.tracking.NormalizedImageTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTextureTransformTest {

    @Test
    fun displayToBufferIsInverseOfLandmarkTransform() {
        listOf(
            0 to false,
            90 to false,
            180 to true,
            270 to true,
        ).forEach { (rotation, mirror) ->
            val forward = NormalizedImageTransform(rotation, mirror)
            val rawX = 0.23f
            val rawY = 0.68f
            val displayX = forward.mapX(rawX, rawY)
            val displayY = forward.mapY(rawX, rawY)

            val mapped = CameraTextureTransform.mapDisplayToBuffer(
                displayX = displayX,
                displayY = displayY,
                bufferWidth = 640,
                bufferHeight = 480,
                crop = CameraTextureTransform.CropRegion(0, 0, 640, 480),
                rotationDegrees = rotation,
                mirrorHorizontal = mirror,
            )

            assertEquals(rawX, mapped.x, EPSILON)
            assertEquals(rawY, mapped.y, EPSILON)
        }
    }

    @Test
    fun cropRegionIsNormalizedAgainstWholeBuffer() {
        val mappedTopLeft = CameraTextureTransform.mapDisplayToBuffer(
            displayX = 0f,
            displayY = 0f,
            bufferWidth = 800,
            bufferHeight = 600,
            crop = CameraTextureTransform.CropRegion(100, 50, 700, 550),
            rotationDegrees = 0,
            mirrorHorizontal = false,
        )
        val mappedBottomRight = CameraTextureTransform.mapDisplayToBuffer(
            displayX = 1f,
            displayY = 1f,
            bufferWidth = 800,
            bufferHeight = 600,
            crop = CameraTextureTransform.CropRegion(100, 50, 700, 550),
            rotationDegrees = 0,
            mirrorHorizontal = false,
        )

        assertEquals(0.125f, mappedTopLeft.x, EPSILON)
        assertEquals(50f / 600f, mappedTopLeft.y, EPSILON)
        assertEquals(0.875f, mappedBottomRight.x, EPSILON)
        assertEquals(550f / 600f, mappedBottomRight.y, EPSILON)
    }

    @Test
    fun affineMatrixMatchesDirectMapping() {
        val crop = CameraTextureTransform.CropRegion(20, 40, 620, 440)
        val matrix = CameraTextureTransform.matrix(
            bufferWidth = 640,
            bufferHeight = 480,
            crop = crop,
            rotationDegrees = 270,
            mirrorHorizontal = true,
            invertDisplayHorizontally = false,
            invertDisplayVertically = false,
        )
        val direct = CameraTextureTransform.mapFilamentUvToTexture(
            filamentU = 0.31f,
            filamentV = 0.72f,
            bufferWidth = 640,
            bufferHeight = 480,
            crop = crop,
            rotationDegrees = 270,
            mirrorHorizontal = true,
            invertDisplayHorizontally = false,
            invertDisplayVertically = false,
        )
        val matrixX = matrix[0] * 0.31f + matrix[4] * 0.72f + matrix[12]
        val matrixY = matrix[1] * 0.31f + matrix[5] * 0.72f + matrix[13]

        assertEquals(direct.x, matrixX, EPSILON)
        assertEquals(direct.y, matrixY, EPSILON)
    }

    @Test
    fun filamentUvConvertsTopLeftImageOriginsAtBothBoundaries() {
        val crop = CameraTextureTransform.CropRegion(0, 0, 640, 480)

        val textureTopLeft = CameraTextureTransform.mapFilamentUvToTexture(
            filamentU = 0f,
            filamentV = 1f,
            bufferWidth = 640,
            bufferHeight = 480,
            crop = crop,
            rotationDegrees = 0,
            mirrorHorizontal = false,
            invertDisplayHorizontally = false,
            invertDisplayVertically = false,
        )
        val textureBottomRight = CameraTextureTransform.mapFilamentUvToTexture(
            filamentU = 1f,
            filamentV = 0f,
            bufferWidth = 640,
            bufferHeight = 480,
            crop = crop,
            rotationDegrees = 0,
            mirrorHorizontal = false,
            invertDisplayHorizontally = false,
            invertDisplayVertically = false,
        )

        assertEquals(0f, textureTopLeft.x, EPSILON)
        assertEquals(1f, textureTopLeft.y, EPSILON)
        assertEquals(1f, textureBottomRight.x, EPSILON)
        assertEquals(0f, textureBottomRight.y, EPSILON)
    }

    @Test
    fun acquiredHardwareBufferCompensationFlipsBothDisplayAxes() {
        val crop = CameraTextureTransform.CropRegion(0, 0, 640, 480)
        val compensated = CameraTextureTransform.mapFilamentUvToTexture(
            filamentU = 0.31f,
            filamentV = 0.72f,
            bufferWidth = 640,
            bufferHeight = 480,
            crop = crop,
            rotationDegrees = 270,
            mirrorHorizontal = true,
            invertDisplayHorizontally = true,
            invertDisplayVertically = true,
        )
        val reflectedBaseline = CameraTextureTransform.mapFilamentUvToTexture(
            filamentU = 1f - 0.31f,
            filamentV = 1f - 0.72f,
            bufferWidth = 640,
            bufferHeight = 480,
            crop = crop,
            rotationDegrees = 270,
            mirrorHorizontal = true,
            invertDisplayHorizontally = false,
            invertDisplayVertically = false,
        )

        assertEquals(reflectedBaseline.x, compensated.x, EPSILON)
        assertEquals(reflectedBaseline.y, compensated.y, EPSILON)
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
