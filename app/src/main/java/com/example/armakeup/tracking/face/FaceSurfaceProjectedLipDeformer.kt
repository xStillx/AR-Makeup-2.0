package com.example.armakeup.tracking.face

/** Applies composed lip-loop deformation to the shared projected face surface. */
internal object FaceSurfaceProjectedLipDeformer {

    fun deform(
        displayLandmarks: FaceLandmarkSet,
        outerGeometry: FaceRegionGeometry,
        innerGeometry: FaceRegionGeometry,
        outerIndices: IntArray,
        innerIndices: IntArray,
    ): FloatArray {
        require(displayLandmarks.coordinateSpace ==
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT)
        require(outerGeometry.region == FaceRegion.LIPS_OUTER)
        require(innerGeometry.region == FaceRegion.LIPS_INNER)
        require(outerGeometry.pointCount == outerIndices.size)
        require(innerGeometry.pointCount == innerIndices.size)

        val deformed = displayLandmarks.packedCopy()
        applyLoop(deformed, displayLandmarks.pointCount, outerGeometry, outerIndices)
        applyLoop(deformed, displayLandmarks.pointCount, innerGeometry, innerIndices)
        return deformed
    }

    private fun applyLoop(
        coordinates: FloatArray,
        pointCount: Int,
        geometry: FaceRegionGeometry,
        indices: IntArray,
    ) {
        indices.forEachIndexed { geometryIndex, landmarkIndex ->
            if (landmarkIndex !in 0 until pointCount) return@forEachIndexed
            val coordinate = landmarkIndex * FaceLandmarkSet.COMPONENT_COUNT
            coordinates[coordinate] = geometry.x(geometryIndex)
            coordinates[coordinate + 1] = geometry.y(geometryIndex)
        }
    }
}
