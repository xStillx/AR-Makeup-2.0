package com.example.armakeup.render

import android.os.Build
import android.view.Choreographer
import androidx.annotation.RequiresApi

/** Immutable copy of the preferred Android frame timeline, valid outside the callback. */
internal data class RenderFrameTimeline(
    val frameTimeNanos: Long,
    val vsyncId: Long,
    val expectedPresentationTimeNanos: Long,
    val deadlineNanos: Long,
)

/**
 * Debug-only timeline observer used while an .arv6 recording is active.
 *
 * The observer is posted before Filament's ChoreographerHelper so [consume] can match the exact
 * frame time without changing Filament's production scheduler or desired-presentation contract.
 */
internal interface RenderFrameTimelineObserver {
    fun start()

    fun stop()

    fun consume(frameTimeNanos: Long): RenderFrameTimeline?
}

internal fun createRenderFrameTimelineObserver(): RenderFrameTimelineObserver =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Api33RenderFrameTimelineObserver()
    } else {
        NoRenderFrameTimelineObserver
    }

private object NoRenderFrameTimelineObserver : RenderFrameTimelineObserver {
    override fun start() = Unit

    override fun stop() = Unit

    override fun consume(frameTimeNanos: Long): RenderFrameTimeline? = null
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Api33RenderFrameTimelineObserver :
    RenderFrameTimelineObserver,
    Choreographer.VsyncCallback {
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var latestTimeline: RenderFrameTimeline? = null

    override fun start() {
        if (running) return
        running = true
        latestTimeline = null
        choreographer.postVsyncCallback(this)
    }

    override fun stop() {
        if (!running) return
        running = false
        latestTimeline = null
        choreographer.removeVsyncCallback(this)
    }

    override fun onVsync(data: Choreographer.FrameData) {
        if (!running) return
        val preferredTimeline = data.preferredFrameTimeline
        latestTimeline = RenderFrameTimeline(
            frameTimeNanos = data.frameTimeNanos,
            vsyncId = preferredTimeline.vsyncId,
            expectedPresentationTimeNanos = preferredTimeline.expectedPresentationTimeNanos,
            deadlineNanos = preferredTimeline.deadlineNanos,
        )
        choreographer.postVsyncCallback(this)
    }

    override fun consume(frameTimeNanos: Long): RenderFrameTimeline? {
        val timeline = latestTimeline ?: return null
        if (timeline.frameTimeNanos <= frameTimeNanos) {
            latestTimeline = null
        }
        return timeline.takeIf { it.frameTimeNanos == frameTimeNanos }
    }
}
