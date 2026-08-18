package com.example.armakeup.render

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderFrameTimelineObserverTest {
    @Test
    fun convertsPastAndFutureMonotonicTimestampsIntoElapsedRealtimeClock() {
        val callbackMonotonicNs = 5_000_000_000L
        val callbackElapsedRealtimeNs = 9_000_000_000L

        assertEquals(
            8_980_000_000L,
            monotonicToElapsedRealtimeTimestampNs(
                monotonicTimestampNs = 4_980_000_000L,
                callbackMonotonicTimestampNs = callbackMonotonicNs,
                callbackElapsedRealtimeTimestampNs = callbackElapsedRealtimeNs,
            ),
        )
        assertEquals(
            9_033_000_000L,
            monotonicToElapsedRealtimeTimestampNs(
                monotonicTimestampNs = 5_033_000_000L,
                callbackMonotonicTimestampNs = callbackMonotonicNs,
                callbackElapsedRealtimeTimestampNs = callbackElapsedRealtimeNs,
            ),
        )
    }

    @Test
    fun preservesUnknownTimestamp() {
        assertEquals(
            -1L,
            monotonicToElapsedRealtimeTimestampNs(
                monotonicTimestampNs = -1L,
                callbackMonotonicTimestampNs = 5_000_000_000L,
                callbackElapsedRealtimeTimestampNs = 9_000_000_000L,
            ),
        )
    }
}
