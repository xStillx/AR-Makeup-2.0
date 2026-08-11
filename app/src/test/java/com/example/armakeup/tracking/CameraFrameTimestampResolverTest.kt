package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFrameTimestampResolverTest {

    @Test
    fun sensorFrameAgeIsIncludedInUptimeTimestamp() {
        val resolver = CameraFrameTimestampResolver()

        val timestampMs = resolver.resolve(
            cameraTimestampNs = 9_950_000_000L,
            nowElapsedRealtimeNs = 10_000_000_000L,
            nowUptimeMs = 5_000L,
        )

        assertEquals(4_950L, timestampMs)
    }

    @Test
    fun incompatibleCameraTimebaseFallsBackToCurrentUptime() {
        val resolver = CameraFrameTimestampResolver(maxPlausibleFrameAgeMs = 500L)

        val timestampMs = resolver.resolve(
            cameraTimestampNs = 1_000_000_000L,
            nowElapsedRealtimeNs = 10_000_000_000L,
            nowUptimeMs = 5_000L,
        )

        assertEquals(5_000L, timestampMs)
    }

    @Test
    fun mediaPipeTimestampsRemainStrictlyIncreasing() {
        val resolver = CameraFrameTimestampResolver()
        val first = resolver.resolve(
            cameraTimestampNs = 9_980_000_000L,
            nowElapsedRealtimeNs = 10_000_000_000L,
            nowUptimeMs = 5_000L,
        )
        val second = resolver.resolve(
            cameraTimestampNs = 9_970_000_000L,
            nowElapsedRealtimeNs = 10_010_000_000L,
            nowUptimeMs = 5_010L,
        )

        assertEquals(first + 1L, second)
    }
}
