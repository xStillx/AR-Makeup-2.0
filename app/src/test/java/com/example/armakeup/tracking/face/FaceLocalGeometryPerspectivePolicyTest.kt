package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLocalGeometryPerspectivePolicyTest {
    private val policy = FaceLocalGeometryPerspectivePolicy.profileSafe()

    @Test
    fun `neutral face keeps complete local deformation`() {
        assertEquals(1f, policy.localWeight(observation(identityMatrix())), EPSILON)
    }

    @Test
    fun `profile yaw uses current global 3D geometry`() {
        val weight = policy.localWeight(
            observation(rotationY(Math.toRadians(60.0).toFloat())),
        )

        assertEquals(0f, weight, EPSILON)
    }

    @Test
    fun `large pitch uses current global 3D geometry`() {
        val weight = policy.localWeight(
            observation(rotationX(Math.toRadians(45.0).toFloat())),
        )

        assertEquals(0f, weight, EPSILON)
    }

    @Test
    fun `transition to profile geometry is gradual`() {
        val weight = policy.localWeight(
            observation(rotationY(Math.toRadians(42.0).toFloat())),
        )

        assertTrue(weight in 0.45f..0.55f)
    }

    private fun observation(faceToWorld: FloatArray): FaceObservation {
        val topology = FaceTopologyDescriptor("policy-test", 1, 1)
        val coordinates = floatArrayOf(0f, 0f, 0f)
        return FaceObservation(
            backendId = "global",
            role = FaceObservationRole.GLOBAL_POSE,
            sensorTimestampNs = 1L,
            topology = topology,
            canonicalLandmarks = FaceLandmarkSet.of(
                topology,
                FaceCoordinateSpace.FACE_LOCAL_METERS,
                coordinates,
            ),
            displayLandmarks = FaceLandmarkSet.of(
                topology,
                FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
                coordinates,
            ),
            pose = FacePose(FaceMatrix4.columnMajor(faceToWorld)),
            cameraFrame = FaceCameraFrameMetadata(
                sensorTimestampNs = 1L,
                viewportWidth = 1080,
                viewportHeight = 1920,
                displayRotationDegrees = 0,
                analysisImageRotationDegrees = 0,
                frontCameraMirrored = true,
                clipFromCamera = FaceMatrix4.columnMajor(identityMatrix()),
                cameraFromWorld = FaceMatrix4.columnMajor(identityMatrix()),
            ),
            quality = FaceObservationQuality(tracking = true),
        )
    }

    private fun rotationX(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, cosine, sine, 0f,
            0f, -sine, cosine, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun rotationY(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            cosine, 0f, -sine, 0f,
            0f, 1f, 0f, 0f,
            sine, 0f, cosine, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun identityMatrix(): FloatArray = FloatArray(16) { index ->
        if (index % 5 == 0) 1f else 0f
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
