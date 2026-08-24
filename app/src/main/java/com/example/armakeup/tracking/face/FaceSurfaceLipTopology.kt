package com.example.armakeup.tracking.face

/**
 * Extracts the semantic lip band from the canonical 468-point face surface.
 *
 * A retained triangle has its face-local centroid inside the outer lip loop and outside the inner
 * mouth loop. Reusing the original face indices keeps lipstick and the depth occluder on exactly
 * the same rasterized surface instead of interpolating depth over an unrelated 2D lip mesh.
 */
internal object FaceSurfaceLipTopology {

    fun extractBandIndices(
        surface: FaceSurfaceTopology,
        canonicalLandmarks: FaceLandmarkSet,
        outerContour: IntArray,
        innerContour: IntArray,
    ): ShortArray {
        require(canonicalLandmarks.topology == surface.topology)
        require(canonicalLandmarks.coordinateSpace == FaceCoordinateSpace.FACE_LOCAL_METERS)
        val pointCount = surface.topology.pointCount
        val outer = outerContour.filter { it in 0 until pointCount }.toIntArray()
        val inner = innerContour.filter { it in 0 until pointCount }.toIntArray()
        if (outer.size < MINIMUM_POLYGON_POINTS || inner.size < MINIMUM_POLYGON_POINTS) {
            return ShortArray(0)
        }

        val selected = ArrayList<Short>()
        var offset = 0
        while (offset < surface.indexCount) {
            val first = surface[offset]
            val second = surface[offset + 1]
            val third = surface[offset + 2]
            val centroidX = (canonicalLandmarks.x(first) + canonicalLandmarks.x(second) +
                canonicalLandmarks.x(third)) / 3f
            val centroidY = (canonicalLandmarks.y(first) + canonicalLandmarks.y(second) +
                canonicalLandmarks.y(third)) / 3f
            if (contains(outer, canonicalLandmarks, centroidX, centroidY) &&
                !contains(inner, canonicalLandmarks, centroidX, centroidY)
            ) {
                selected += first.toShort()
                selected += second.toShort()
                selected += third.toShort()
            }
            offset += FaceSurfaceTopology.INDICES_PER_TRIANGLE
        }
        return ShortArray(selected.size) { selected[it] }
    }

    private fun contains(
        polygon: IntArray,
        landmarks: FaceLandmarkSet,
        x: Float,
        y: Float,
    ): Boolean {
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            val currentX = landmarks.x(current)
            val currentY = landmarks.y(current)
            val previousX = landmarks.x(previous)
            val previousY = landmarks.y(previous)
            if ((currentY > y) != (previousY > y)) {
                val intersectionX = (previousX - currentX) * (y - currentY) /
                    (previousY - currentY) + currentX
                if (x < intersectionX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private const val MINIMUM_POLYGON_POINTS = 3
}
