package com.example.armakeup.tracking.face

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceSurfaceCameraLipDeformerTest {

    @Test
    fun `moves covered intermediate vertex with semantic camera-space lip motion`() {
        val topology = FaceTopologyDescriptor("camera-lip-deform", 1, 6)
        val canonical = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.FACE_LOCAL_METERS,
            floatArrayOf(
                -0.6f, -0.4f, 0.2f,
                0.6f, -0.4f, 0.2f,
                -0.3f, 0.3f, 0.2f,
                0.3f, 0.3f, 0.2f,
                0f, 0f, 0.2f,
                0.8f, 0.8f, 0.2f,
            ),
        )
        val display = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
            floatArrayOf(
                0.2f, 0.7f, 0.2f,
                0.8f, 0.7f, 0.2f,
                0.35f, 0.35f, 0.2f,
                0.65f, 0.35f, 0.2f,
                0.5f, 0.5f, 0.2f,
                0.9f, 0.1f, 0.2f,
            ),
        )
        val identity = FaceMatrix4.columnMajor(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        assertArrayEquals(
            floatArrayOf(
                0.3f, 0.7f, 0.2f,
                0.9f, 0.7f, 0.2f,
                0.45f, 0.35f, 0.2f,
                0.75f, 0.35f, 0.2f,
                0.6f, 0.5f, 0.2f,
                0.9f, 0.1f, 0.2f,
            ),
            FaceSurfaceCameraLipDeformer.deform(
                canonicalLandmarks = canonical,
                displayLandmarks = display,
                textureCoordinates = FaceSurfaceTextureCoordinates.of(
                    topology,
                    floatArrayOf(
                        -1f, -1f,
                        1f, -1f,
                        -0.5f, 0.5f,
                        0.5f, 0.5f,
                        0f, 0f,
                        2f, 2f,
                    ),
                ),
                deformationSupport = floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f),
                facePose = FacePose(identity),
                cameraFrame = FaceCameraFrameMetadata(
                    sensorTimestampNs = 1L,
                    viewportWidth = 100,
                    viewportHeight = 100,
                    displayRotationDegrees = 0,
                    analysisImageRotationDegrees = 0,
                    frontCameraMirrored = true,
                    clipFromCamera = identity,
                    cameraFromWorld = identity,
                ),
                outerGeometry = FaceRegionGeometry.of(
                    FaceRegion.LIPS_OUTER,
                    floatArrayOf(0.3f, 0.7f, 0.9f, 0.7f),
                ),
                innerGeometry = FaceRegionGeometry.of(
                    FaceRegion.LIPS_INNER,
                    floatArrayOf(0.45f, 0.35f, 0.75f, 0.35f),
                ),
                outerIndices = intArrayOf(0, 1),
                innerIndices = intArrayOf(2, 3),
            ),
            1e-6f,
        )
    }
}
