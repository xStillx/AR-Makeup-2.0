package com.example.armakeup.tracking

import com.example.armakeup.render.VulkanTemporalTrackingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TemporalLandmarkRefinerTest {
    @Test
    fun composesContiguousFlowAfterAnchor() {
        val refiner = TemporalLandmarkRefiner(maxRenderExtrapolationNs = 0L)
        refiner.offer(sample(1_000L, 34_000_000L, translationX = 0.01f), 100L)
        refiner.offer(sample(34_000_000L, 67_000_000L, translationX = 0.02f), 200L)

        val correction = refiner.correctionFor(1_000L, 200L)
        assertNotNull(correction)

        assertEquals(0.53f, correction!!.mapX(0.5f, 0.5f), 0.0001f)
        assertEquals(0.5f, correction.mapY(0.5f, 0.5f), 0.0001f)
    }

    @Test
    fun interpolatesFirstIntervalWhenAnchorFallsInsideIt() {
        val refiner = TemporalLandmarkRefiner(maxRenderExtrapolationNs = 0L)
        refiner.offer(sample(1_000L, 40_001_000L, translationX = 0.04f), 100L)

        val correction = refiner.correctionFor(20_001_000L, 100L)
        assertNotNull(correction)

        assertEquals(0.52f, correction!!.mapX(0.5f, 0.5f), 0.0001f)
    }

    @Test
    fun rejectsTimestampGapInsteadOfPropagatingDrift() {
        val refiner = TemporalLandmarkRefiner(
            maxGapNs = 50_000_000L,
            maxRenderExtrapolationNs = 0L,
        )
        refiner.offer(sample(100_000_000L, 133_000_000L), 100L)

        assertNull(refiner.correctionFor(1_000L, 100L))
    }

    @Test
    fun extrapolatesOnlyWithinRenderWindow() {
        val refiner = TemporalLandmarkRefiner(maxRenderExtrapolationNs = 20_000_000L)
        refiner.offer(
            sample(1_000L, 20_001_000L, translationX = 0.02f),
            deliveryElapsedRealtimeNs = 1_000_000_000L,
        )

        val correction = refiner.correctionFor(1_000L, 1_100_000_000L)
        assertNotNull(correction)

        assertEquals(0.54f, correction!!.mapX(0.5f, 0.5f), 0.0001f)
    }

    private fun sample(
        from: Long,
        to: Long,
        translationX: Float = 0f,
    ) = VulkanTemporalTrackingResult(
        fromSensorTimestampNs = from,
        toSensorTimestampNs = to,
        scaleCos = 1f,
        scaleSin = 0f,
        translationX = translationX,
        translationY = 0f,
        confidence = 0.8f,
        rmsResidual = 0.003f,
        inlierCount = 32,
    )
}
