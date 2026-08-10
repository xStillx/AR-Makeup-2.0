package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class FillCenterTransformTest {

    @Test
    fun portraitSourceFillsPortraitViewAndCropsHorizontally() {
        val transform = FillCenterTransform.calculate(
            viewWidth = 1080,
            viewHeight = 1920,
            sourceWidth = 480,
            sourceHeight = 640,
        )

        assertEquals(3f, transform.scale, 0.001f)
        assertEquals(-180f, transform.offsetX, 0.001f)
        assertEquals(0f, transform.offsetY, 0.001f)
        assertEquals(540f, transform.mapX(0.5f, 480), 0.001f)
        assertEquals(960f, transform.mapY(0.5f, 640), 0.001f)
    }

    @Test
    fun matchingAspectRatioNeedsNoOffset() {
        val transform = FillCenterTransform.calculate(
            viewWidth = 1280,
            viewHeight = 720,
            sourceWidth = 640,
            sourceHeight = 360,
        )

        assertEquals(2f, transform.scale, 0.001f)
        assertEquals(0f, transform.offsetX, 0.001f)
        assertEquals(0f, transform.offsetY, 0.001f)
    }

    @Test
    fun frontCameraCoordinateIsMirroredAroundCenter() {
        val transform = FillCenterTransform.calculate(
            viewWidth = 100,
            viewHeight = 100,
            sourceWidth = 100,
            sourceHeight = 100,
        )

        assertEquals(75f, transform.mapX(0.25f, 100, mirrorHorizontal = true), 0.001f)
    }
}
