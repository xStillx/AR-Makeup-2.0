package com.example.armakeup.tracking.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridFullFaceStateComposerTest {
    private val topology = FaceTopologyDescriptor("synthetic-face", 1, 6)
    private val composer = HybridFullFaceStateComposer(
        stableAnchorIndices = intArrayOf(0, 1, 2, 3),
        outerLipIndices = intArrayOf(4),
        innerLipIndices = intArrayOf(5),
        upperInnerLipIndex = 4,
        lowerInnerLipIndex = 5,
        maximumGlobalAffineResidual = 0.02f,
    )

    @Test
    fun `maps local lip shape into current global display state`() {
        val localCoordinates = sourceCoordinates()
        val globalCoordinates = mapCoordinates(localCoordinates)
        val global = globalObservation(globalCoordinates, sensorTimestampNs = 10_000_000L)
        val local = localObservation(localCoordinates, sensorTimestampNs = 7_000_000L)

        val state = composer.compose(global, local, renderTimestampNs = 11_000_000L)

        assertNotNull(state)
        state!!
        assertEquals(10_000_000L, state.cameraSensorTimestampNs)
        assertEquals(7_000_000L, state.localObservationTimestampNs)
        assertEquals(3_000_000L, state.attachmentQuality.localObservationAgeNs)
        assertTrue(state.attachmentQuality.localDeformationApplied)
        assertTrue((state.attachmentQuality.affineFitResidualNormalized ?: 1f) < 1e-5f)
        assertEquals(globalCoordinates[4 * 3], state.region(FaceRegion.LIPS_OUTER)!!.x(0), 1e-5f)
        assertEquals(globalCoordinates[4 * 3 + 1], state.region(FaceRegion.LIPS_OUTER)!!.y(0), 1e-5f)
        assertEquals(globalCoordinates[5 * 3], state.region(FaceRegion.LIPS_INNER)!!.x(0), 1e-5f)
        assertEquals(
            (globalCoordinates[4 * 3] + globalCoordinates[5 * 3]) * 0.5f,
            state.lipAnchor!!.x,
            1e-5f,
        )
    }

    @Test
    fun `current mouth anchor corrects translation without inheriting global mouth stretch`() {
        val mouthTopology = FaceTopologyDescriptor("mouth-frame", 1, 10)
        val mouthComposer = HybridFullFaceStateComposer(
            stableAnchorIndices = intArrayOf(0, 1, 2, 3),
            outerLipIndices = intArrayOf(8),
            innerLipIndices = intArrayOf(9),
            upperInnerLipIndex = 8,
            lowerInnerLipIndex = 9,
            maximumGlobalAffineResidual = 0.02f,
        )
        val localCoordinates = floatArrayOf(
            0.1f, 0.1f, 0f,
            0.9f, 0.1f, 0f,
            0.1f, 0.9f, 0f,
            0.9f, 0.9f, 0f,
            0.35f, 0.45f, 0f,
            0.65f, 0.45f, 0f,
            0.35f, 0.55f, 0f,
            0.65f, 0.55f, 0f,
            0.50f, 0.47f, 0f,
            0.50f, 0.53f, 0f,
        )
        val currentGlobalCoordinates = mapWithSplitFrames(localCoordinates)
        val global = observationForTopology(
            topology = mouthTopology,
            displayCoordinates = currentGlobalCoordinates,
            sensorTimestampNs = 20L,
            role = FaceObservationRole.GLOBAL_POSE,
        )
        val local = observationForTopology(
            topology = mouthTopology,
            displayCoordinates = localCoordinates,
            sensorTimestampNs = 10L,
            role = FaceObservationRole.LOCAL_DEFORMATION,
        )

        val state = mouthComposer.compose(global, local, renderTimestampNs = 21L)

        assertNotNull(state)
        assertTrue(state!!.attachmentQuality.localDeformationApplied)
        val outer = state.region(FaceRegion.LIPS_OUTER)!!
        val inner = state.region(FaceRegion.LIPS_INNER)!!
        val targetCenterY = (
            currentGlobalCoordinates[8 * 3 + 1] + currentGlobalCoordinates[9 * 3 + 1]
            ) * 0.5f
        assertEquals(targetCenterY, (outer.y(0) + inner.y(0)) * 0.5f, 1e-5f)
        assertEquals(0.6f * (localCoordinates[9 * 3 + 1] - localCoordinates[8 * 3 + 1]),
            inner.y(0) - outer.y(0), 1e-5f)
        assertTrue(kotlin.math.abs(inner.y(0) - outer.y(0)) <
            kotlin.math.abs(currentGlobalCoordinates[9 * 3 + 1] - currentGlobalCoordinates[8 * 3 + 1]))
    }

    @Test
    fun `preserves mouth translation relative to stable face anchors`() {
        val mouthTopology = FaceTopologyDescriptor("mouth-expression-translation", 1, 10)
        val mouthComposer = HybridFullFaceStateComposer(
            stableAnchorIndices = intArrayOf(0, 1, 2, 3),
            outerLipIndices = intArrayOf(8),
            innerLipIndices = intArrayOf(9),
            upperInnerLipIndex = 8,
            lowerInnerLipIndex = 9,
            maximumGlobalAffineResidual = 0.02f,
        )
        val localCoordinates = floatArrayOf(
            0.1f, 0.1f, 0f,
            0.9f, 0.1f, 0f,
            0.1f, 0.9f, 0f,
            0.9f, 0.9f, 0f,
            0.35f, 0.45f, 0f,
            0.65f, 0.45f, 0f,
            0.35f, 0.55f, 0f,
            0.65f, 0.55f, 0f,
            0.62f, 0.47f, 0f,
            0.62f, 0.53f, 0f,
        )
        val currentGlobalCoordinates = mapCoordinates(mouthTopology, localCoordinates).also {
            // ARCore's global mesh keeps the semantic mouth anchor near the neutral face center.
            it[8 * 3] = 0.45f
            it[9 * 3] = 0.45f
        }
        val global = observationForTopology(
            topology = mouthTopology,
            displayCoordinates = currentGlobalCoordinates,
            sensorTimestampNs = 20L,
            role = FaceObservationRole.GLOBAL_POSE,
        )
        val local = observationForTopology(
            topology = mouthTopology,
            displayCoordinates = localCoordinates,
            sensorTimestampNs = 10L,
            role = FaceObservationRole.LOCAL_DEFORMATION,
        )

        val state = mouthComposer.compose(global, local, renderTimestampNs = 21L)

        assertNotNull(state)
        val expectedExpressionX = 0.7f * localCoordinates[8 * 3] + 0.1f
        val outer = state!!.region(FaceRegion.LIPS_OUTER)!!
        val inner = state.region(FaceRegion.LIPS_INNER)!!
        assertEquals(expectedExpressionX, outer.x(0), 1e-5f)
        assertEquals(expectedExpressionX, inner.x(0), 1e-5f)
        assertTrue(outer.x(0) > currentGlobalCoordinates[8 * 3])
    }

    @Test
    fun `backend implementations can be replaced without changing composer`() {
        val localCoordinates = sourceCoordinates()
        val global = globalObservation(mapCoordinates(localCoordinates), sensorTimestampNs = 10L)
        val local = localObservation(localCoordinates, sensorTimestampNs = 8L)
        val globalBackend = FakeBackend(global)
        val localBackend = FakeBackend(local)

        val state = composer.compose(globalBackend, localBackend, renderTimestampNs = 11L)

        assertNotNull(state)
        assertTrue(state!!.attachmentQuality.localDeformationApplied)
        assertEquals("synthetic-face", state.globalTopology.id)
    }

    @Test
    fun `keeps current global anchor when local observation is unavailable`() {
        val globalCoordinates = mapCoordinates(sourceCoordinates())

        val state = composer.compose(
            globalObservation(globalCoordinates, sensorTimestampNs = 10L),
            localObservation = null,
            renderTimestampNs = 11L,
        )

        assertNotNull(state)
        assertNotNull(state!!.lipAnchor)
        assertFalse(state.attachmentQuality.localDeformationApplied)
        assertEquals(globalCoordinates[4 * 3], state.region(FaceRegion.LIPS_OUTER)!!.x(0), 1e-5f)
        assertEquals(globalCoordinates[5 * 3], state.region(FaceRegion.LIPS_INNER)!!.x(0), 1e-5f)
        assertNull(state.localObservationTimestampNs)
    }

    @Test
    fun `rejects local shape when shared anchor fit is inconsistent`() {
        val localCoordinates = sourceCoordinates()
        val inconsistentGlobal = mapCoordinates(localCoordinates).also { coordinates ->
            coordinates[3 * 3] += 0.25f
            coordinates[3 * 3 + 1] -= 0.2f
        }

        val state = composer.compose(
            globalObservation(inconsistentGlobal, sensorTimestampNs = 20L),
            localObservation(localCoordinates, sensorTimestampNs = 10L),
            renderTimestampNs = 21L,
        )

        assertNotNull(state)
        assertFalse(state!!.attachmentQuality.localDeformationApplied)
        assertTrue((state.attachmentQuality.affineFitResidualNormalized ?: 0f) > 0.02f)
        assertEquals(
            inconsistentGlobal[4 * 3],
            state.region(FaceRegion.LIPS_OUTER)!!.x(0),
            1e-5f,
        )
    }

    @Test
    fun `fades local deformation as affine residual approaches rejection`() {
        val residualAwareComposer = HybridFullFaceStateComposer(
            stableAnchorIndices = intArrayOf(0, 1, 2, 3),
            outerLipIndices = intArrayOf(4),
            innerLipIndices = intArrayOf(5),
            upperInnerLipIndex = 4,
            lowerInnerLipIndex = 5,
            fullLocalAffineResidual = 0.002f,
            maximumGlobalAffineResidual = 0.02f,
        )
        val localCoordinates = sourceCoordinates()
        val perturbedGlobal = mapCoordinates(localCoordinates).also { coordinates ->
            coordinates[3 * 3] += 0.01f
        }

        val state = residualAwareComposer.compose(
            globalObservation(perturbedGlobal, sensorTimestampNs = 20L),
            localObservation(localCoordinates, sensorTimestampNs = 10L),
            renderTimestampNs = 21L,
        )

        assertNotNull(state)
        val residual = state!!.attachmentQuality.affineFitResidualNormalized!!
        val weight = state.attachmentQuality.localDeformationWeight!!
        assertTrue(residual in 0.002f..0.02f)
        assertTrue(weight in 0f..1f)
        assertTrue(weight < 1f)
        assertTrue(state.attachmentQuality.localDeformationApplied)
    }

    @Test
    fun `rejects stale local deformation and keeps current global lips`() {
        val ageBoundedComposer = HybridFullFaceStateComposer(
            stableAnchorIndices = intArrayOf(0, 1, 2, 3),
            outerLipIndices = intArrayOf(4),
            innerLipIndices = intArrayOf(5),
            upperInnerLipIndex = 4,
            lowerInnerLipIndex = 5,
            maximumGlobalAffineResidual = 0.02f,
            maximumLocalObservationAgeNs = 100L,
        )
        val localCoordinates = sourceCoordinates()
        val globalCoordinates = mapCoordinates(localCoordinates)

        val state = ageBoundedComposer.compose(
            globalObservation(globalCoordinates, sensorTimestampNs = 500L),
            localObservation(localCoordinates, sensorTimestampNs = 399L),
            renderTimestampNs = 501L,
        )

        assertNotNull(state)
        assertFalse(state!!.attachmentQuality.localDeformationApplied)
        assertEquals(0f, state.attachmentQuality.localDeformationWeight!!, 0f)
        assertEquals(globalCoordinates[4 * 3], state.region(FaceRegion.LIPS_OUTER)!!.x(0), 1e-5f)
        assertNull(state.localObservationTimestampNs)
    }

    @Test
    fun `publishes independently sourced eye states and normalized aperture`() {
        val featureTopology = FaceTopologyDescriptor("feature-face", 1, 14)
        val featureComposer = HybridFullFaceStateComposer(
            stableAnchorIndices = intArrayOf(0, 1, 2, 3),
            outerLipIndices = intArrayOf(4),
            innerLipIndices = intArrayOf(5),
            leftEyeIndices = intArrayOf(6, 8, 7, 9),
            rightEyeIndices = intArrayOf(10, 12, 11, 13),
            mouthAperture = FaceApertureTopology(4, 5, 4, 5),
            leftEyeAperture = FaceApertureTopology(6, 7, 8, 9),
            rightEyeAperture = FaceApertureTopology(10, 11, 12, 13),
            upperInnerLipIndex = 4,
            lowerInnerLipIndex = 5,
            maximumGlobalAffineResidual = 0.02f,
        )
        val localCoordinates = floatArrayOf(
            0.1f, 0.1f, 0f,
            0.9f, 0.1f, 0f,
            0.1f, 0.9f, 0f,
            0.9f, 0.9f, 0f,
            0.45f, 0.48f, 0f,
            0.55f, 0.52f, 0f,
            0.20f, 0.30f, 0f,
            0.40f, 0.30f, 0f,
            0.30f, 0.28f, 0f,
            0.30f, 0.32f, 0f,
            0.60f, 0.30f, 0f,
            0.80f, 0.30f, 0f,
            0.70f, 0.28f, 0f,
            0.70f, 0.32f, 0f,
        )
        val globalCoordinates = mapCoordinates(featureTopology, localCoordinates)
        val global = observationForTopology(
            topology = featureTopology,
            displayCoordinates = globalCoordinates,
            sensorTimestampNs = 20L,
            role = FaceObservationRole.GLOBAL_POSE,
            features = FaceObservationFeatures(
                mouth = FaceFeatureObservationState(tracking = true),
                leftEye = FaceFeatureObservationState(tracking = true),
                rightEye = FaceFeatureObservationState(tracking = true),
            ),
        )
        val local = observationForTopology(
            topology = featureTopology,
            displayCoordinates = localCoordinates,
            sensorTimestampNs = 10L,
            role = FaceObservationRole.LOCAL_DEFORMATION,
            features = FaceObservationFeatures(
                mouth = FaceFeatureObservationState(tracking = true),
                leftEye = FaceFeatureObservationState(tracking = true),
                rightEye = FaceFeatureObservationState(tracking = false),
            ),
        )

        val state = featureComposer.compose(global, local, renderTimestampNs = 21L)

        assertNotNull(state)
        state!!
        assertEquals(
            FaceFeatureGeometrySource.LOCAL_DEFORMATION,
            state.features.leftEye.geometrySource,
        )
        assertEquals(10L, state.features.leftEye.observationTimestampNs)
        assertEquals(0.6f * 0.04f / (0.7f * 0.20f), state.features.leftEye.apertureRatio!!, 1e-5f)
        assertEquals(
            FaceFeatureGeometrySource.GLOBAL_FALLBACK,
            state.features.rightEye.geometrySource,
        )
        assertEquals(20L, state.features.rightEye.observationTimestampNs)
        assertNotNull(state.region(FaceRegion.LEFT_EYE))
        assertNotNull(state.region(FaceRegion.RIGHT_EYE))
        assertNull(state.features.leftEye.confidence)
        assertNull(state.features.leftEye.visibleFraction)
    }

    private fun globalObservation(
        displayCoordinates: FloatArray,
        sensorTimestampNs: Long,
    ): FaceObservation {
        val canonical = FloatArray(topology.pointCount * 3) { index ->
            when (index % 3) {
                0 -> index / 3 * 0.001f
                1 -> index / 3 * -0.001f
                else -> -0.5f
            }
        }
        return FaceObservation(
            backendId = "global",
            role = FaceObservationRole.GLOBAL_POSE,
            sensorTimestampNs = sensorTimestampNs,
            topology = topology,
            canonicalLandmarks = FaceLandmarkSet.of(
                topology,
                FaceCoordinateSpace.FACE_LOCAL_METERS,
                canonical,
            ),
            displayLandmarks = FaceLandmarkSet.of(
                topology,
                FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
                displayCoordinates,
            ),
            pose = FacePose(FaceMatrix4.columnMajor(identityMatrix())),
            cameraFrame = FaceCameraFrameMetadata(
                sensorTimestampNs = sensorTimestampNs,
                viewportWidth = 1080,
                viewportHeight = 1920,
                displayRotationDegrees = 0,
                analysisImageRotationDegrees = 0,
                frontCameraMirrored = true,
                clipFromCamera = FaceMatrix4.columnMajor(identityMatrix()),
                cameraFromWorld = FaceMatrix4.columnMajor(identityMatrix()),
            ),
            quality = FaceObservationQuality(tracking = true, visibleFraction = 1f),
        )
    }

    private fun localObservation(
        coordinates: FloatArray,
        sensorTimestampNs: Long,
    ): FaceObservation = FaceObservation(
        backendId = "local",
        role = FaceObservationRole.LOCAL_DEFORMATION,
        sensorTimestampNs = sensorTimestampNs,
        topology = topology,
        imageLandmarks = FaceLandmarkSet.of(
            topology,
            FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
            coordinates,
        ),
        quality = FaceObservationQuality(tracking = true),
        diagnostics = FaceObservationDiagnostics(
            resultTimestampNs = sensorTimestampNs + 1L,
            inferenceDurationMs = 22f,
            conversionDurationMs = 3f,
            smoothedFps = 30f,
        ),
    )

    private fun sourceCoordinates(): FloatArray = floatArrayOf(
        0.1f, 0.1f, 0f,
        0.9f, 0.1f, 0f,
        0.1f, 0.9f, 0f,
        0.9f, 0.9f, 0f,
        0.4f, 0.5f, 0f,
        0.6f, 0.5f, 0f,
    )

    private fun mapCoordinates(source: FloatArray): FloatArray = FloatArray(source.size).also {
        repeat(topology.pointCount) { pointIndex ->
            val index = pointIndex * 3
            it[index] = 0.7f * source[index] + 0.1f
            it[index + 1] = 0.6f * source[index + 1] + 0.2f
            it[index + 2] = 0f
        }
    }

    private fun mapCoordinates(
        topology: FaceTopologyDescriptor,
        source: FloatArray,
    ): FloatArray = FloatArray(source.size).also {
        repeat(topology.pointCount) { pointIndex ->
            val index = pointIndex * 3
            it[index] = 0.7f * source[index] + 0.1f
            it[index + 1] = 0.6f * source[index + 1] + 0.2f
            it[index + 2] = 0f
        }
    }

    private fun mapWithSplitFrames(source: FloatArray): FloatArray = FloatArray(source.size).also {
        repeat(source.size / 3) { pointIndex ->
            val index = pointIndex * 3
            if (pointIndex < 4) {
                it[index] = 0.7f * source[index] + 0.1f
                it[index + 1] = 0.6f * source[index + 1] + 0.2f
            } else {
                it[index] = 0.5f * source[index] + 0.3f
                it[index + 1] = 1.2f * source[index + 1] - 0.1f
            }
        }
    }

    private fun observationForTopology(
        topology: FaceTopologyDescriptor,
        displayCoordinates: FloatArray,
        sensorTimestampNs: Long,
        role: FaceObservationRole,
        features: FaceObservationFeatures = FaceObservationFeatures(),
    ): FaceObservation {
        if (role == FaceObservationRole.LOCAL_DEFORMATION) {
            return FaceObservation(
                backendId = "local-split",
                role = role,
                sensorTimestampNs = sensorTimestampNs,
                topology = topology,
                imageLandmarks = FaceLandmarkSet.of(
                    topology,
                    FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
                    displayCoordinates,
                ),
                quality = FaceObservationQuality(tracking = true),
                features = features,
            )
        }
        return FaceObservation(
            backendId = "global-split",
            role = role,
            sensorTimestampNs = sensorTimestampNs,
            topology = topology,
            canonicalLandmarks = FaceLandmarkSet.of(
                topology,
                FaceCoordinateSpace.FACE_LOCAL_METERS,
                FloatArray(topology.pointCount * 3),
            ),
            displayLandmarks = FaceLandmarkSet.of(
                topology,
                FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
                displayCoordinates,
            ),
            pose = FacePose(FaceMatrix4.columnMajor(identityMatrix())),
            cameraFrame = FaceCameraFrameMetadata(
                sensorTimestampNs = sensorTimestampNs,
                viewportWidth = 1080,
                viewportHeight = 1920,
                displayRotationDegrees = 0,
                analysisImageRotationDegrees = 0,
                frontCameraMirrored = true,
                clipFromCamera = FaceMatrix4.columnMajor(identityMatrix()),
                cameraFromWorld = FaceMatrix4.columnMajor(identityMatrix()),
            ),
            quality = FaceObservationQuality(tracking = true),
            features = features,
        )
    }

    private fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private class FakeBackend(
        private val observation: FaceObservation,
    ) : FaceTrackingBackend {
        override val backendId: String = observation.backendId
        override val role: FaceObservationRole = observation.role
        override val topology: FaceTopologyDescriptor = observation.topology

        override fun latestObservation(): FaceObservation = observation

        override fun close() = Unit
    }
}
