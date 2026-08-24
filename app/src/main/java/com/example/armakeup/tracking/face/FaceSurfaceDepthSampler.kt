package com.example.armakeup.tracking.face

import kotlin.math.abs

/**
 * Reconstructs the projected face depth under a screen-space product vertex.
 *
 * Depth is interpolated only from triangles touching the requested semantic landmark set. This
 * keeps the per-frame lip tessellation bounded while retaining the same projected surface that the
 * Vulkan face occluder rasterizes. If no projected triangle contains the sample, the nearest
 * preferred landmark supplies a conservative fallback.
 */
internal class FaceSurfaceDepthSampler(
    private val topology: FaceSurfaceTopology,
    preferredLandmarkIndices: IntArray,
) {
    private val preferredLandmarkIndices = preferredLandmarkIndices
        .distinct()
        .filter { it in 0 until topology.topology.pointCount }
        .toIntArray()
    private val candidateTriangleOffsets = buildCandidateTriangleOffsets()

    init {
        require(this.preferredLandmarkIndices.isNotEmpty())
    }

    fun ndcDepthAt(
        displayLandmarks: FaceLandmarkSet,
        x: Float,
        y: Float,
    ): Float? {
        require(displayLandmarks.topology == topology.topology)
        require(displayLandmarks.coordinateSpace ==
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT)
        if (!x.isFinite() || !y.isFinite()) return null

        var nearestDepth: Float? = null
        candidateTriangleOffsets.forEach { offset ->
            val first = topology[offset]
            val second = topology[offset + 1]
            val third = topology[offset + 2]
            val depth = interpolatedDepth(
                displayLandmarks = displayLandmarks,
                first = first,
                second = second,
                third = third,
                x = x,
                y = y,
            ) ?: return@forEach
            if (nearestDepth == null || depth < checkNotNull(nearestDepth)) {
                nearestDepth = depth
            }
        }
        return nearestDepth ?: nearestLandmarkDepth(displayLandmarks, x, y)
    }

    private fun buildCandidateTriangleOffsets(): IntArray {
        val preferred = BooleanArray(topology.topology.pointCount)
        preferredLandmarkIndices.forEach { preferred[it] = true }
        val offsets = ArrayList<Int>()
        var offset = 0
        while (offset < topology.indexCount) {
            if (preferred[topology[offset]] ||
                preferred[topology[offset + 1]] ||
                preferred[topology[offset + 2]]
            ) {
                offsets += offset
            }
            offset += FaceSurfaceTopology.INDICES_PER_TRIANGLE
        }
        if (offsets.isNotEmpty()) return offsets.toIntArray()
        return IntArray(topology.triangleCount) {
            it * FaceSurfaceTopology.INDICES_PER_TRIANGLE
        }
    }

    private fun interpolatedDepth(
        displayLandmarks: FaceLandmarkSet,
        first: Int,
        second: Int,
        third: Int,
        x: Float,
        y: Float,
    ): Float? {
        val firstX = displayLandmarks.x(first)
        val firstY = displayLandmarks.y(first)
        val secondX = displayLandmarks.x(second)
        val secondY = displayLandmarks.y(second)
        val thirdX = displayLandmarks.x(third)
        val thirdY = displayLandmarks.y(third)
        val denominator = (secondY - thirdY) * (firstX - thirdX) +
            (thirdX - secondX) * (firstY - thirdY)
        if (!denominator.isFinite() || abs(denominator) <= MINIMUM_TRIANGLE_AREA) return null

        val firstWeight = ((secondY - thirdY) * (x - thirdX) +
            (thirdX - secondX) * (y - thirdY)) / denominator
        val secondWeight = ((thirdY - firstY) * (x - thirdX) +
            (firstX - thirdX) * (y - thirdY)) / denominator
        val thirdWeight = 1f - firstWeight - secondWeight
        if (firstWeight < -BARYCENTRIC_EDGE_TOLERANCE ||
            secondWeight < -BARYCENTRIC_EDGE_TOLERANCE ||
            thirdWeight < -BARYCENTRIC_EDGE_TOLERANCE
        ) {
            return null
        }
        return (
            firstWeight * displayLandmarks.z(first) +
                secondWeight * displayLandmarks.z(second) +
                thirdWeight * displayLandmarks.z(third)
            ).takeIf { it.isFinite() }
    }

    private fun nearestLandmarkDepth(
        displayLandmarks: FaceLandmarkSet,
        x: Float,
        y: Float,
    ): Float? {
        var nearestDistanceSquared = Float.POSITIVE_INFINITY
        var nearestDepth: Float? = null
        preferredLandmarkIndices.forEach { index ->
            val deltaX = displayLandmarks.x(index) - x
            val deltaY = displayLandmarks.y(index) - y
            val distanceSquared = deltaX * deltaX + deltaY * deltaY
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared
                nearestDepth = displayLandmarks.z(index)
            }
        }
        return nearestDepth?.takeIf { it.isFinite() }
    }

    private companion object {
        const val MINIMUM_TRIANGLE_AREA = 1e-9f
        const val BARYCENTRIC_EDGE_TOLERANCE = 0.015f
    }
}
