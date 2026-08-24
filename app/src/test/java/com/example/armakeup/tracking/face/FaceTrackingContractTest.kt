package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FaceTrackingContractTest {

    @Test
    fun `landmark and matrix storage is immutable at the public boundary`() {
        val topology = FaceTopologyDescriptor("test", 1, 2)
        val coordinates = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val matrixValues = identityMatrix()
        val landmarks = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
            coordinates,
        )
        val matrix = FaceMatrix4.columnMajor(matrixValues)

        coordinates[0] = 9f
        matrixValues[0] = 9f
        val exported = landmarks.packedCopy().also { it[0] = 8f }
        val exportedMatrix = matrix.packedCopy().also { it[0] = 8f }

        assertEquals(0.1f, landmarks.x(0), 0f)
        assertEquals(1f, matrix[0], 0f)
        assertNotEquals(exported[0], landmarks.x(0))
        assertNotEquals(exportedMatrix[0], matrix[0])
    }

    @Test
    fun `observation rejects landmarks from another topology`() {
        val declaredTopology = FaceTopologyDescriptor("declared", 1, 1)
        val actualTopology = FaceTopologyDescriptor("actual", 1, 1)
        val landmarks = FaceLandmarkSet.of(
            actualTopology,
            FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
            floatArrayOf(0f, 0f, 0f),
        )

        assertThrows(IllegalArgumentException::class.java) {
            FaceObservation(
                backendId = "test",
                role = FaceObservationRole.LOCAL_DEFORMATION,
                sensorTimestampNs = 1L,
                topology = declaredTopology,
                imageLandmarks = landmarks,
                quality = FaceObservationQuality(tracking = true),
            )
        }
    }

    private fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
