package com.example.armakeup.tracking

/** Model-independent normalized 2D contour produced by a face-tracking backend. */
internal data class NormalizedContour2D(
    val points: FloatArray,
) {
    init {
        require(points.size >= MINIMUM_COMPONENT_COUNT && points.size % POINT_COMPONENT_COUNT == 0)
        require(points.all { it.isFinite() })
    }

    val pointCount: Int
        get() = points.size / POINT_COMPONENT_COUNT

    fun deepCopy(): NormalizedContour2D = NormalizedContour2D(points.copyOf())

    private companion object {
        const val POINT_COMPONENT_COUNT = 2
        const val MINIMUM_COMPONENT_COUNT = 6
    }
}

/** Analytic semantic baseline. A future parsing model can replace these probabilities. */
internal data class LipSemanticState(
    val lipConfidence: Float,
    val mouthTeethExclusionConfidence: Float,
    val edgeRefinementStrength: Float,
) {
    init {
        require(lipConfidence in 0f..1f)
        require(mouthTeethExclusionConfidence in 0f..1f)
        require(edgeRefinementStrength in 0f..1f)
    }
}

/** Timestamped local lip measurement independent from MediaPipe and ARCore types. */
internal data class LipContourObservation(
    val outer: NormalizedContour2D,
    val inner: NormalizedContour2D,
    val geometryConfidence: Float,
    val mouthOpenness: Float,
    val semantics: LipSemanticState,
    val topologyVersion: Int = CURRENT_TOPOLOGY_VERSION,
) {
    init {
        require(outer.pointCount == inner.pointCount)
        require(geometryConfidence in 0f..1f)
        require(mouthOpenness in 0f..1f)
        require(topologyVersion > 0)
    }

    companion object {
        const val CURRENT_TOPOLOGY_VERSION = 1
    }
}

/** One backend observation on the original camera sensor timeline. */
internal data class FaceObservation(
    val sensorTimestampNs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val mirrorHorizontal: Boolean,
    val lips: LipContourObservation,
) {
    init {
        require(sensorTimestampNs > 0L)
        require(sourceWidth > 0)
        require(sourceHeight > 0)
    }
}
