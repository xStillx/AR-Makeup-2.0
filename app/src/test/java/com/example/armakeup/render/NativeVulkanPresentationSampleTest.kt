package com.example.armakeup.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVulkanPresentationSampleTest {
    @Test
    fun sensorToActualUsesTheNormalizedElapsedRealtimeClock() {
        val sample = NativeVulkanPresentationSample(
            presentationId = 17L,
            cameraSensorTimestampNs = 1_000_000_000L,
            actualPresentationTimestampNs = 1_127_500_000L,
            desiredPresentationTimestampNs = 0L,
            earliestPresentationTimestampNs = 0L,
            presentMarginNs = 14_000_000L,
            refreshDurationNs = 16_666_667L,
        )

        assertEquals(127.5f, sample.sensorToActualMs, 0.0001f)
    }

    @Test
    fun missingCameraTimestampDoesNotInventLatency() {
        val sample = NativeVulkanPresentationSample(
            presentationId = 1L,
            cameraSensorTimestampNs = -1L,
            actualPresentationTimestampNs = 10L,
            desiredPresentationTimestampNs = 0L,
            earliestPresentationTimestampNs = 0L,
            presentMarginNs = 0L,
            refreshDurationNs = 0L,
        )

        assertTrue(sample.sensorToActualMs.isNaN())
    }
}
