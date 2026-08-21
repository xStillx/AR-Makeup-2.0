package com.example.armakeup.render

import com.example.armakeup.tracking.TrackingRenderBackend
import com.example.armakeup.tracking.TrackingRenderSample
import com.example.armakeup.tracking.TrackingRenderTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun presentationTelemetryResolvesTheExactSubmittedGeometry() {
        val telemetry = NativeVulkanPresentationTelemetry(maximumPendingSamples = 2)
        val submitted = renderSample(presentationId = 17L, outerX = 0.42f)

        assertNull(telemetry.add(submitted))
        val resolved = telemetry.resolve(
            presentationSample(
                presentationId = 17L,
                cameraSensorTimestampNs = 1_000_000_000L,
                actualPresentationTimestampNs = 1_127_000_000L,
            ),
        )

        assertNotNull(resolved)
        assertEquals(0.42f, resolved!!.outerLipPoints[0], 0f)
        assertEquals(1_000_000_000L, resolved.renderTiming.cameraFrameSensorTimestampNs)
        assertEquals(1_127_000_000L, resolved.renderTiming.presentationTimestampNs)
        assertEquals(0, telemetry.pendingCount)
    }

    @Test
    fun presentationTelemetryBoundsAndFlushesUnresolvedSamples() {
        val telemetry = NativeVulkanPresentationTelemetry(maximumPendingSamples = 2)
        telemetry.add(renderSample(1L, 0.1f))
        telemetry.add(renderSample(2L, 0.2f))

        val evicted = telemetry.add(renderSample(3L, 0.3f))
        val unresolved = telemetry.drainUnresolved()

        assertEquals(1L, evicted!!.presentationId)
        assertEquals(listOf(2L, 3L), unresolved.map { it.presentationId })
        assertEquals(0, telemetry.pendingCount)
    }

    private fun renderSample(presentationId: Long, outerX: Float) = TrackingRenderSample(
        renderTimestampMs = 1_100L,
        measurementTimestampMs = 1_000L,
        sensorTimestampNs = 999_000_000L,
        predictionSeconds = 0.05f,
        viewportWidth = 1080,
        viewportHeight = 2340,
        lipVisible = true,
        outerLipPoints = floatArrayOf(outerX, 0.5f),
        innerLipPoints = floatArrayOf(0.5f, 0.5f),
        renderBackend = TrackingRenderBackend.NATIVE_VULKAN,
        presentationId = presentationId,
        renderTiming = TrackingRenderTiming.UNKNOWN,
    )

    private fun presentationSample(
        presentationId: Long,
        cameraSensorTimestampNs: Long,
        actualPresentationTimestampNs: Long,
    ) = NativeVulkanPresentationSample(
        presentationId = presentationId,
        cameraSensorTimestampNs = cameraSensorTimestampNs,
        actualPresentationTimestampNs = actualPresentationTimestampNs,
        desiredPresentationTimestampNs = 0L,
        earliestPresentationTimestampNs = 0L,
        presentMarginNs = 0L,
        refreshDurationNs = 16_666_667L,
    )
}
