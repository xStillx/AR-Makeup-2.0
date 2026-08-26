package com.example.armakeup.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampedLipAnchorTransportTest {
    @Test
    fun exactCameraTimestampsProduceAnchorDelta() {
        val transport = TimestampedLipAnchorTransport()
        transport.record(100_000_000L, 0.40f, 0.55f)
        transport.record(133_000_000L, 0.46f, 0.51f)

        val correction = transport.correctionFor(100_000_000L, 133_000_000L)!!

        assertEquals(0.06f, correction.translationX, 1e-6f)
        assertEquals(-0.04f, correction.translationY, 1e-6f)
    }

    @Test
    fun measurementBetweenCameraFramesIsInterpolated() {
        val transport = TimestampedLipAnchorTransport()
        transport.record(100L, 0.20f, 0.30f)
        transport.record(200L, 0.40f, 0.50f)

        val correction = transport.correctionFor(150L, 200L)!!

        assertEquals(0.10f, correction.translationX, 1e-6f)
        assertEquals(0.10f, correction.translationY, 1e-6f)
    }

    @Test
    fun unavailableOldMeasurementDoesNotInventCorrection() {
        val transport = TimestampedLipAnchorTransport(
            maximumHistoryNs = 1_000L,
            maximumLookupDistanceNs = 10L,
        )
        transport.record(2_000L, 0.3f, 0.4f)
        transport.record(3_100L, 0.4f, 0.5f)
        transport.record(3_200L, 0.5f, 0.6f)

        assertNull(transport.correctionFor(2_000L, 3_200L))
    }

    @Test
    fun resetDropsPreviousSessionHistory() {
        val transport = TimestampedLipAnchorTransport()
        transport.record(100_000_000L, 0.2f, 0.3f)
        transport.reset()
        transport.record(200_000_000L, 0.4f, 0.5f)

        assertNull(transport.correctionFor(100_000_000L, 200_000_000L))
    }
}
