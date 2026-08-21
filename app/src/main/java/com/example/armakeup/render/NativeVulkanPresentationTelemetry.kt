package com.example.armakeup.render

import com.example.armakeup.tracking.TrackingRenderSample

/** Correlates delayed Vulkan display feedback with the exact geometry submitted for that present. */
internal class NativeVulkanPresentationTelemetry(
    private val maximumPendingSamples: Int = DEFAULT_MAXIMUM_PENDING_SAMPLES,
) {
    private val pendingSamples = LinkedHashMap<Long, TrackingRenderSample>()

    init {
        require(maximumPendingSamples > 0)
    }

    val pendingCount: Int
        get() = pendingSamples.size

    /** Returns an unresolved sample only when the bounded pending window has to evict it. */
    fun add(sample: TrackingRenderSample): TrackingRenderSample? {
        require(sample.presentationId > 0L)
        val replaced = pendingSamples.put(sample.presentationId, sample)
        if (replaced != null) return replaced
        if (pendingSamples.size <= maximumPendingSamples) return null
        val iterator = pendingSamples.entries.iterator()
        val evicted = iterator.next().value
        iterator.remove()
        return evicted
    }

    fun resolve(presentation: NativeVulkanPresentationSample): TrackingRenderSample? {
        val submitted = pendingSamples.remove(presentation.presentationId) ?: return null
        return submitted.copy(
            renderTiming = submitted.renderTiming.copy(
                cameraFrameSensorTimestampNs = presentation.cameraSensorTimestampNs,
                presentationTimestampNs = presentation.actualPresentationTimestampNs,
            ),
        )
    }

    fun drainUnresolved(): List<TrackingRenderSample> {
        if (pendingSamples.isEmpty()) return emptyList()
        return pendingSamples.values.toList().also { pendingSamples.clear() }
    }

    companion object {
        private const val DEFAULT_MAXIMUM_PENDING_SAMPLES = 256
    }
}
