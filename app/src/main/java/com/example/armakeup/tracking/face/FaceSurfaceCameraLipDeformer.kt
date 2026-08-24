package com.example.armakeup.tracking.face

import kotlin.math.abs

/**
 * Extends composed lip-loop motion across the canonical lip band in camera space.
 *
 * ARCore remains the owner of the current face pose and surface depth. MediaPipe supplies target
 * display positions for the semantic lip loops. Each loop target is unprojected at its current
 * ARCore camera depth, its camera-space displacement is interpolated over canonical UV, and the
 * shared face surface is projected again. Face-depth and lipstick therefore consume the same
 * locally deformed vertices rather than leaving covered intermediate vertices on the rigid mesh.
 */
internal object FaceSurfaceCameraLipDeformer {

    fun deform(
        canonicalLandmarks: FaceLandmarkSet,
        displayLandmarks: FaceLandmarkSet,
        textureCoordinates: FaceSurfaceTextureCoordinates,
        deformationSupport: FloatArray,
        facePose: FacePose,
        cameraFrame: FaceCameraFrameMetadata,
        outerGeometry: FaceRegionGeometry,
        innerGeometry: FaceRegionGeometry,
        outerIndices: IntArray,
        innerIndices: IntArray,
    ): FloatArray {
        require(canonicalLandmarks.coordinateSpace == FaceCoordinateSpace.FACE_LOCAL_METERS)
        require(displayLandmarks.coordinateSpace ==
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT)
        require(canonicalLandmarks.topology == displayLandmarks.topology)
        require(textureCoordinates.topology == displayLandmarks.topology)
        require(deformationSupport.size == displayLandmarks.pointCount)
        require(outerGeometry.region == FaceRegion.LIPS_OUTER)
        require(innerGeometry.region == FaceRegion.LIPS_INNER)
        require(outerGeometry.pointCount == outerIndices.size)
        require(innerGeometry.pointCount == innerIndices.size)

        val controls = buildControls(
            canonicalLandmarks = canonicalLandmarks,
            textureCoordinates = textureCoordinates,
            facePose = facePose,
            cameraFrame = cameraFrame,
            outerGeometry = outerGeometry,
            innerGeometry = innerGeometry,
            outerIndices = outerIndices,
            innerIndices = innerIndices,
        )
        if (controls.isEmpty()) return displayLandmarks.packedCopy()

        val deformed = displayLandmarks.packedCopy()
        val controlVertices = BooleanArray(displayLandmarks.pointCount)
        controls.forEach { control ->
            controlVertices[control.vertexIndex] = true
        }

        repeat(displayLandmarks.pointCount) { vertexIndex ->
            if (deformationSupport[vertexIndex] <= 0f || controlVertices[vertexIndex]) {
                return@repeat
            }
            val cameraPoint = cameraPoint(
                canonicalLandmarks = canonicalLandmarks,
                vertexIndex = vertexIndex,
                facePose = facePose,
                cameraFrame = cameraFrame,
            )
            val displacement = interpolateDisplacement(
                u = textureCoordinates.u(vertexIndex),
                v = textureCoordinates.v(vertexIndex),
                controls = controls,
            )
            writeProjected(
                destination = deformed,
                vertexIndex = vertexIndex,
                cameraX = cameraPoint.x + displacement.x,
                cameraY = cameraPoint.y + displacement.y,
                cameraZ = cameraPoint.z + displacement.z,
                projection = cameraFrame.clipFromCamera,
            )
        }

        controls.forEach { control ->
            writeProjected(
                destination = deformed,
                vertexIndex = control.vertexIndex,
                cameraX = control.targetCameraX,
                cameraY = control.targetCameraY,
                cameraZ = control.targetCameraZ,
                projection = cameraFrame.clipFromCamera,
            )
        }
        return deformed
    }

    private fun buildControls(
        canonicalLandmarks: FaceLandmarkSet,
        textureCoordinates: FaceSurfaceTextureCoordinates,
        facePose: FacePose,
        cameraFrame: FaceCameraFrameMetadata,
        outerGeometry: FaceRegionGeometry,
        innerGeometry: FaceRegionGeometry,
        outerIndices: IntArray,
        innerIndices: IntArray,
    ): List<Control> = buildList(outerIndices.size + innerIndices.size) {
        addLoopControls(
            canonicalLandmarks = canonicalLandmarks,
            textureCoordinates = textureCoordinates,
            facePose = facePose,
            cameraFrame = cameraFrame,
            geometry = outerGeometry,
            indices = outerIndices,
            destination = this,
        )
        addLoopControls(
            canonicalLandmarks = canonicalLandmarks,
            textureCoordinates = textureCoordinates,
            facePose = facePose,
            cameraFrame = cameraFrame,
            geometry = innerGeometry,
            indices = innerIndices,
            destination = this,
        )
    }

    private fun addLoopControls(
        canonicalLandmarks: FaceLandmarkSet,
        textureCoordinates: FaceSurfaceTextureCoordinates,
        facePose: FacePose,
        cameraFrame: FaceCameraFrameMetadata,
        geometry: FaceRegionGeometry,
        indices: IntArray,
        destination: MutableList<Control>,
    ) {
        indices.forEachIndexed { geometryIndex, vertexIndex ->
            if (vertexIndex !in 0 until canonicalLandmarks.pointCount) return@forEachIndexed
            val current = cameraPoint(
                canonicalLandmarks = canonicalLandmarks,
                vertexIndex = vertexIndex,
                facePose = facePose,
                cameraFrame = cameraFrame,
            )
            val target = unprojectAtCameraDepth(
                normalizedDisplayX = geometry.x(geometryIndex),
                normalizedDisplayY = geometry.y(geometryIndex),
                cameraZ = current.z,
                projection = cameraFrame.clipFromCamera,
            ) ?: return@forEachIndexed
            destination += Control(
                vertexIndex = vertexIndex,
                u = textureCoordinates.u(vertexIndex),
                v = textureCoordinates.v(vertexIndex),
                targetCameraX = target.x,
                targetCameraY = target.y,
                targetCameraZ = target.z,
                displacementX = target.x - current.x,
                displacementY = target.y - current.y,
                displacementZ = target.z - current.z,
            )
        }
    }

    private fun cameraPoint(
        canonicalLandmarks: FaceLandmarkSet,
        vertexIndex: Int,
        facePose: FacePose,
        cameraFrame: FaceCameraFrameMetadata,
    ): Point3 {
        val world = transformPoint(
            matrix = facePose.faceToWorld,
            x = canonicalLandmarks.x(vertexIndex),
            y = canonicalLandmarks.y(vertexIndex),
            z = canonicalLandmarks.z(vertexIndex),
        )
        return transformPoint(
            matrix = cameraFrame.cameraFromWorld,
            x = world.x,
            y = world.y,
            z = world.z,
        )
    }

    private fun transformPoint(matrix: FaceMatrix4, x: Float, y: Float, z: Float): Point3 {
        val transformedX = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
        val transformedY = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
        val transformedZ = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        val transformedW = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15]
        if (!transformedW.isFinite() || abs(transformedW) <= MINIMUM_VALUE) {
            return Point3(transformedX, transformedY, transformedZ)
        }
        return Point3(
            transformedX / transformedW,
            transformedY / transformedW,
            transformedZ / transformedW,
        )
    }

    private fun unprojectAtCameraDepth(
        normalizedDisplayX: Float,
        normalizedDisplayY: Float,
        cameraZ: Float,
        projection: FaceMatrix4,
    ): Point3? {
        val ndcX = normalizedDisplayX * 2f - 1f
        val ndcY = 1f - normalizedDisplayY * 2f
        val firstX = projection[0] - ndcX * projection[3]
        val firstY = projection[4] - ndcX * projection[7]
        val firstValue = -(
            (projection[8] - ndcX * projection[11]) * cameraZ +
                projection[12] - ndcX * projection[15]
            )
        val secondX = projection[1] - ndcY * projection[3]
        val secondY = projection[5] - ndcY * projection[7]
        val secondValue = -(
            (projection[9] - ndcY * projection[11]) * cameraZ +
                projection[13] - ndcY * projection[15]
            )
        val determinant = firstX * secondY - firstY * secondX
        if (!determinant.isFinite() || abs(determinant) <= MINIMUM_VALUE) return null
        val cameraX = (firstValue * secondY - firstY * secondValue) / determinant
        val cameraY = (firstX * secondValue - firstValue * secondX) / determinant
        if (!cameraX.isFinite() || !cameraY.isFinite() || !cameraZ.isFinite()) return null
        return Point3(cameraX, cameraY, cameraZ)
    }

    private fun interpolateDisplacement(u: Float, v: Float, controls: List<Control>): Point3 {
        val nearestIndices = IntArray(INTERPOLATION_NEIGHBOR_COUNT) { -1 }
        val nearestDistances = FloatArray(INTERPOLATION_NEIGHBOR_COUNT) {
            Float.POSITIVE_INFINITY
        }
        controls.forEachIndexed { controlIndex, control ->
            val deltaU = u - control.u
            val deltaV = v - control.v
            val distanceSquared = deltaU * deltaU + deltaV * deltaV
            var insertionIndex = nearestDistances.lastIndex
            if (distanceSquared >= nearestDistances[insertionIndex]) return@forEachIndexed
            while (insertionIndex > 0 &&
                distanceSquared < nearestDistances[insertionIndex - 1]
            ) {
                nearestDistances[insertionIndex] = nearestDistances[insertionIndex - 1]
                nearestIndices[insertionIndex] = nearestIndices[insertionIndex - 1]
                insertionIndex--
            }
            nearestDistances[insertionIndex] = distanceSquared
            nearestIndices[insertionIndex] = controlIndex
        }

        var weightedX = 0f
        var weightedY = 0f
        var weightedZ = 0f
        var totalWeight = 0f
        nearestIndices.forEachIndexed { nearestIndex, controlIndex ->
            if (controlIndex < 0) return@forEachIndexed
            val weight = 1f / maxOf(nearestDistances[nearestIndex], MINIMUM_DISTANCE_SQUARED)
            val control = controls[controlIndex]
            weightedX += control.displacementX * weight
            weightedY += control.displacementY * weight
            weightedZ += control.displacementZ * weight
            totalWeight += weight
        }
        if (!totalWeight.isFinite() || totalWeight <= 0f) return Point3.ZERO
        return Point3(
            weightedX / totalWeight,
            weightedY / totalWeight,
            weightedZ / totalWeight,
        )
    }

    private fun writeProjected(
        destination: FloatArray,
        vertexIndex: Int,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
        projection: FaceMatrix4,
    ) {
        val clipX = projection[0] * cameraX + projection[4] * cameraY +
            projection[8] * cameraZ + projection[12]
        val clipY = projection[1] * cameraX + projection[5] * cameraY +
            projection[9] * cameraZ + projection[13]
        val clipZ = projection[2] * cameraX + projection[6] * cameraY +
            projection[10] * cameraZ + projection[14]
        val clipW = projection[3] * cameraX + projection[7] * cameraY +
            projection[11] * cameraZ + projection[15]
        if (!clipW.isFinite() || abs(clipW) <= MINIMUM_VALUE) return
        val coordinate = vertexIndex * FaceLandmarkSet.COMPONENT_COUNT
        destination[coordinate] = (clipX / clipW + 1f) * 0.5f
        destination[coordinate + 1] = (1f - clipY / clipW) * 0.5f
        destination[coordinate + 2] = clipZ / clipW
    }

    private data class Point3(val x: Float, val y: Float, val z: Float) {
        companion object {
            val ZERO = Point3(0f, 0f, 0f)
        }
    }

    private data class Control(
        val vertexIndex: Int,
        val u: Float,
        val v: Float,
        val targetCameraX: Float,
        val targetCameraY: Float,
        val targetCameraZ: Float,
        val displacementX: Float,
        val displacementY: Float,
        val displacementZ: Float,
    )

    private const val INTERPOLATION_NEIGHBOR_COUNT = 4
    private const val MINIMUM_VALUE = 1e-7f
    private const val MINIMUM_DISTANCE_SQUARED = 1e-10f
}
