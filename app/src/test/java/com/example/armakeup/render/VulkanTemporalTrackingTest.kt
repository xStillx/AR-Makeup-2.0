package com.example.armakeup.render

import org.junit.Assert.assertEquals
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
                values = floatArrayOf(1f, 0f, 0.12f, 0f, 0.9f, 0.002f, 30f, 1f),
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
    fun nativeResultAllowsCoherentFastInterFrameTranslation() {
        val result = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = 1_000_000_000L,
            toSensorTimestampNs = 1_033_000_000L,
            values = floatArrayOf(1f, 0f, 0.08f, 0f, 0.9f, 0.002f, 30f, 1f),
        )

        assertTrue(result?.passesNativeContract == true)
    }

    @Test
    fun nativeResultRequiresSpatiallySupportedCoherentCoverage() {
        val accepted = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = 1_000_000_000L,
            toSensorTimestampNs = 1_033_000_000L,
            values = floatArrayOf(0.994f, 0.008f, 0.006f, -0.004f, 0.52f, 0.008f, 16f, 1f),
        )
        val tooSparse = VulkanTemporalTrackingResult.fromNative(
            fromSensorTimestampNs = 1_000_000_000L,
            toSensorTimestampNs = 1_033_000_000L,
            values = floatArrayOf(0.994f, 0.008f, 0.006f, -0.004f, 0.52f, 0.008f, 15f, 1f),
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

    @Test
    fun externalCornerFitPreservesTimestampsAndIndependentCornerValidity() {
        val values = FloatArray(VulkanLipCornerTemporalFit.VALUE_COUNT)
        values[0] = 1f
        values[2] = 0.01f
        values[4] = 0.8f
        values[7] = 1f
        values[8] = 0.012f
        values[9] = -0.003f
        values[10] = 0.75f
        values[11] = 1f
        values[12] = 0.008f
        values[13] = 0.002f
        values[14] = 0.3f

        val fit = VulkanLipCornerTemporalFit.fromNative(
            metadata = longArrayOf(1_066_000_000L, 1_000_000_000L, 1_033_000_000L),
            values = values,
        )

        requireNotNull(fit)
        assertEquals(1_000_000_000L, fit.fromSensorTimestampNs)
        assertEquals(1_033_000_000L, fit.toSensorTimestampNs)
        assertTrue(fit.globalValid)
        assertTrue(fit.leftValid)
        assertFalse(fit.rightValid)
        assertEquals(0.012f, fit.leftFlowX, 1e-6f)
    }

    @Test
    fun mouthFlowParsesSourceRoiAndBilinearlySamplesConstantField() {
        val values = FloatArray(VulkanMouthFlowFrame.VALUE_COUNT)
        values[0] = 0.2f
        values[1] = 0.3f
        values[2] = 0.8f
        values[3] = 0.7f
        var index = 4
        while (index < values.size) {
            values[index] = 0.014f
            values[index + 1] = -0.009f
            values[index + 2] = 0.82f
            values[index + 3] = 0.015f
            index += 4
        }

        val frame = VulkanMouthFlowFrame.fromNative(
            metadata = longArrayOf(0L, 1_000_000_000L, 1_033_000_000L),
            values = values,
        )

        requireNotNull(frame)
        val sample = requireNotNull(frame.sample(0.51f, 0.49f))
        assertEquals(0.014f, sample.deltaX, 1e-6f)
        assertEquals(-0.009f, sample.deltaY, 1e-6f)
        assertEquals(0.82f, sample.confidence, 1e-6f)
        assertNull(frame.sample(0.1f, 0.49f))
    }
}
