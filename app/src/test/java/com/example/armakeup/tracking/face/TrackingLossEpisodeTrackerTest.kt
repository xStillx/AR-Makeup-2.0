package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingLossEpisodeTrackerTest {

    @Test
    fun `reports consecutive loss frames and duration on reacquisition`() {
        val tracker = TrackingLossEpisodeTracker()

        assertNull(tracker.record(1_000L, tracking = false))
        assertNull(tracker.record(34_000L, tracking = false))
        assertNull(tracker.record(67_000L, tracking = false))
        val completed = tracker.record(100_000L, tracking = true)!!

        assertEquals(3, completed.frameCount)
        assertEquals(99_000L, completed.durationNs)
        assertEquals(0, tracker.consecutiveLossFrames)
        assertEquals(3L, tracker.totalLossFrames)
        assertEquals(3, tracker.maximumLossFrames)
        assertEquals(99_000L, tracker.maximumLossDurationNs)
        assertEquals(completed, tracker.lastCompletedEpisode)
    }

    @Test
    fun `reset clears loss history`() {
        val tracker = TrackingLossEpisodeTracker()
        tracker.record(1_000L, tracking = false)

        tracker.reset()

        assertEquals(0, tracker.consecutiveLossFrames)
        assertEquals(0L, tracker.totalLossFrames)
        assertEquals(0, tracker.maximumLossFrames)
        assertEquals(0L, tracker.maximumLossDurationNs)
        assertNull(tracker.lastCompletedEpisode)
    }
}
