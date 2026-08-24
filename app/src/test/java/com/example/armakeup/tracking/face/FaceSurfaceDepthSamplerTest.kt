package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceSurfaceDepthSamplerTest {

    @Test
    fun `interpolates projected depth inside semantic surface triangle`() {
        val topology = FaceTopologyDescriptor("depth", 1, 4)
        val surface = FaceSurfaceTopology.of(
            topology,
            shortArrayOf(0, 1, 2, 1, 3, 2),
        )
        val landmarks = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
            floatArrayOf(
                0f, 0f, -0.8f,
                1f, 0f, -0.4f,
                0f, 1f, 0f,
                1f, 1f, 0.4f,
            ),
        )
        val sampler = FaceSurfaceDepthSampler(surface, intArrayOf(0, 1, 2, 3))

        assertEquals(-0.5f, sampler.ndcDepthAt(landmarks, 0.25f, 0.25f)!!, 1e-5f)
        assertEquals(0.1f, sampler.ndcDepthAt(landmarks, 0.75f, 0.75f)!!, 1e-5f)
    }

    @Test
    fun `chooses nearest overlapping projected face surface`() {
        val topology = FaceTopologyDescriptor("overlap", 1, 6)
        val surface = FaceSurfaceTopology.of(
            topology,
            shortArrayOf(0, 1, 2, 3, 4, 5),
        )
        val landmarks = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
            floatArrayOf(
                0f, 0f, 0.5f,
                1f, 0f, 0.5f,
                0f, 1f, 0.5f,
                0f, 0f, -0.5f,
                1f, 0f, -0.5f,
                0f, 1f, -0.5f,
            ),
        )
        val sampler = FaceSurfaceDepthSampler(surface, intArrayOf(0, 1, 2, 3, 4, 5))

        val sample = sampler.sampleAt(landmarks, 0.2f, 0.2f)!!
        assertEquals(-0.5f, sample.ndcDepth, 1e-5f)
        assertEquals(2, sample.triangleHitCount)
        assertEquals(1f, sample.overlappingDepthSpread, 1e-5f)
        assertEquals(false, sample.usedNearestLandmarkFallback)
    }

    @Test
    fun `reports nearest landmark fallback outside candidate triangles`() {
        val topology = FaceTopologyDescriptor("fallback", 1, 3)
        val surface = FaceSurfaceTopology.of(topology, shortArrayOf(0, 1, 2))
        val landmarks = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
            floatArrayOf(0f, 0f, -0.7f, 1f, 0f, -0.2f, 0f, 1f, 0.3f),
        )
        val sampler = FaceSurfaceDepthSampler(surface, intArrayOf(0, 1, 2))

        val sample = sampler.sampleAt(landmarks, 2f, 2f)!!

        assertEquals(-0.2f, sample.ndcDepth, 1e-5f)
        assertEquals(0, sample.triangleHitCount)
        assertEquals(0f, sample.overlappingDepthSpread, 0f)
        assertEquals(true, sample.usedNearestLandmarkFallback)
    }

    @Test
    fun `rejects non finite sample coordinates`() {
        val topology = FaceTopologyDescriptor("invalid", 1, 3)
        val surface = FaceSurfaceTopology.of(topology, shortArrayOf(0, 1, 2))
        val landmarks = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        )
        val sampler = FaceSurfaceDepthSampler(surface, intArrayOf(0, 1, 2))

        assertNull(sampler.ndcDepthAt(landmarks, Float.NaN, 0f))
    }
}
