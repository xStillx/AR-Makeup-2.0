package com.example.armakeup.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanTemporalTrackingTest {
    @Test
    fun nativeResultRejectsLowConfidenceAndImplausibleMotion() {
        assertNull(
            VulkanTemporalTrackingResult.fromNative(
                fromSensorTimestampNs = 1_000_000_000L,
                toSensorTimestampNs = 1_033_000_000L,
                values = floatArrayOf(1f, 0f, 0.08f, 0f, 0.9f, 0.002f, 30f, 1f),
            ),
        )
        assertNull(
            VulkanTemporalTrackingResult.fromNative(
                fromSensorTimestampNs = 1_000_000_000L,
                toSensorTimestampNs = 1_033_000_000L,
                values = floatArrayOf(1f, 0f, 0.01f, 0f, 0.2f, 0.002f, 30f, 1f),
            ),
        )
    }

    @Test
    fun nativeResultAcceptsBoundedSimilarityTransform() {
        val result = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = 1_000_000_000L,
            toSensorTimestampNs = 1_033_000_000L,
            values = floatArrayOf(1.002f, 0.004f, 0.01f, -0.006f, 0.82f, 0.004f, 36f, 1f),
        )

        assertTrue(result?.passesNativeContract == true)
    }

    @Test
    fun nativeResultAdmitsSparseButCoherentTexturedCoverage() {
        val accepted = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = 1_000_000_000L,
            toSensorTimestampNs = 1_033_000_000L,
            values = floatArrayOf(0.994f, 0.008f, 0.006f, -0.004f, 0.38f, 0.009f, 12f, 1f),
        )
        val tooSparse = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = 1_000_000_000L,
            toSensorTimestampNs = 1_033_000_000L,
            values = floatArrayOf(0.994f, 0.008f, 0.006f, -0.004f, 0.38f, 0.009f, 11f, 1f),
        )

        assertTrue(accepted?.passesNativeContract == true)
        assertNull(tooSparse)
    }

    @Test
    fun roiRequiresFiniteBoundedExtent() {
        assertTrue(VulkanTemporalTrackingRoi(0.2f, 0.3f, 0.8f, 0.7f).isValid)
        assertFalse(VulkanTemporalTrackingRoi(-0.1f, 0.3f, 0.8f, 0.7f).isValid)
        assertFalse(VulkanTemporalTrackingRoi(0.2f, 0.3f, 0.2f, 0.7f).isValid)
    }
}
