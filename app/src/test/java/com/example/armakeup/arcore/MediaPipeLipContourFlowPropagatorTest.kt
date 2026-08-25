package com.example.armakeup.arcore

import com.example.armakeup.render.VulkanMouthFlowFrame
import com.example.armakeup.tracking.face.FaceCoordinateSpace
import com.example.armakeup.tracking.face.FaceLandmarkSet
import com.example.armakeup.tracking.face.FaceObservation
import com.example.armakeup.tracking.face.FaceObservationQuality
import com.example.armakeup.tracking.face.FaceObservationRole
import com.example.armakeup.tracking.face.FaceTopologyDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaPipeLipContourFlowPropagatorTest {
    private val topology = FaceTopologyDescriptor("flow-face", 1, 12)
    private val anchors = intArrayOf(0, 1, 2, 3)
    private val outer = intArrayOf(4, 5, 6, 7)
    private val inner = intArrayOf(8, 9, 10, 11)
    private lateinit var propagator: MediaPipeLipContourFlowPropagator

    @Before
    fun setUp() {
        propagator = MediaPipeLipContourFlowPropagator(
            stableAnchorIndices = anchors,
            outerLipIndices = outer,
            innerLipIndices = inner,
            maximumAnchorResidual = 0.02f,
        )
    }

    @Test
    fun undersampledFlowFallsBackInsideArCoreBound() {
        val result = propagateWithFlow(deltaX = 0.01f, deltaY = -0.02f)

        assertNotNull(result)
        requireNotNull(result)
        val globalOnlyX = 0.42f
        val globalOnlyY = 0.46f
        assertTrue(result.outer.x(0) in globalOnlyX..0.426f)
        assertTrue(result.outer.y(0) in 0.452f..globalOnlyY)
        assertEquals(FLOW_TIMESTAMP, result.propagatedToTimestampNs)
        assertEquals(0f, result.meanFlowConfidence, 1e-5f)
    }

    @Test
    fun implausiblePointFlowFallsBackToArCoreMotionWithoutDeformation() {
        val result = propagateWithFlow(deltaX = 0.12f, deltaY = -0.10f)

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(0.42f, result.outer.x(0), 1e-5f)
        assertEquals(0.46f, result.outer.y(0), 1e-5f)
        assertEquals(0f, result.meanFlowConfidence, 1e-5f)
    }

    @Test
    fun systematicVerticalMediaPipeLipOffsetIsReanchoredToArCore() {
        val global = sourceCoordinates()
        val local = global.copyOf().also { coordinates ->
            (outer + inner).forEach { landmark -> coordinates[landmark * 3 + 1] += 0.012f }
        }
        propagator.recordGlobal(globalObservation(FIRST_TIMESTAMP, global))

        val result = propagator.propagate(
            localObservation = localObservation(FIRST_TIMESTAMP, local),
            currentGlobalObservation = globalObservation(FIRST_TIMESTAMP, global),
        )

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(0.45f, result.outer.y(0), 1e-5f)
    }

    @Test
    fun horizontalMediaPipeMouthExpressionSurvivesVerticalReanchor() {
        val global = sourceCoordinates()
        val local = global.copyOf().also { coordinates ->
            (outer + inner).forEach { landmark -> coordinates[landmark * 3] += 0.015f }
        }
        propagator.recordGlobal(globalObservation(FIRST_TIMESTAMP, global))

        val result = propagator.propagate(
            localObservation = localObservation(FIRST_TIMESTAMP, local),
            currentGlobalObservation = globalObservation(FIRST_TIMESTAMP, global),
        )

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(0.415f, result.outer.x(0), 1e-5f)
    }

    private fun propagateWithFlow(deltaX: Float, deltaY: Float): PropagatedLipGeometry? {
        val source = sourceCoordinates()
        propagator.recordGlobal(globalObservation(FIRST_TIMESTAMP, source))
        propagator.recordGlobal(globalObservation(FLOW_TIMESTAMP, source))
        val currentCoordinates = translated(source, 0.02f, 0.01f)
        propagator.recordGlobal(globalObservation(CURRENT_TIMESTAMP, currentCoordinates))
        propagator.recordFlow(
            constantFlow(
                fromTimestampNs = FIRST_TIMESTAMP,
                toTimestampNs = FLOW_TIMESTAMP,
                deltaX = deltaX,
                deltaY = deltaY,
            ),
        )
        return propagator.propagate(
            localObservation = localObservation(FIRST_TIMESTAMP, source),
            currentGlobalObservation = globalObservation(CURRENT_TIMESTAMP, currentCoordinates),
        )
    }

    private fun constantFlow(
        fromTimestampNs: Long,
        toTimestampNs: Long,
        deltaX: Float,
        deltaY: Float,
    ): VulkanMouthFlowFrame {
        val values = FloatArray(VulkanMouthFlowFrame.VALUE_COUNT)
        values[0] = 0.2f
        values[1] = 0.3f
        values[2] = 0.8f
        values[3] = 0.7f
        var index = 4
        while (index < values.size) {
            values[index] = deltaX
            values[index + 1] = deltaY
            values[index + 2] = 0.8f
            values[index + 3] = 0.01f
            index += 4
        }
        return requireNotNull(
            VulkanMouthFlowFrame.fromNative(
                metadata = longArrayOf(0L, fromTimestampNs, toTimestampNs),
                values = values,
            ),
        )
    }

    private fun globalObservation(timestampNs: Long, coordinates: FloatArray): FaceObservation =
        FaceObservation(
            backendId = "global",
            role = FaceObservationRole.GLOBAL_POSE,
            sensorTimestampNs = timestampNs,
            topology = topology,
            displayLandmarks = FaceLandmarkSet.of(
                topology = topology,
                coordinateSpace = FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
                packedCoordinates = coordinates,
            ),
            quality = FaceObservationQuality(tracking = true),
        )

    private fun localObservation(timestampNs: Long, coordinates: FloatArray): FaceObservation =
        FaceObservation(
            backendId = "local",
            role = FaceObservationRole.LOCAL_DEFORMATION,
            sensorTimestampNs = timestampNs,
            topology = topology,
            imageLandmarks = FaceLandmarkSet.of(
                topology = topology,
                coordinateSpace = FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
                packedCoordinates = coordinates,
            ),
            quality = FaceObservationQuality(tracking = true),
        )

    private fun translated(source: FloatArray, deltaX: Float, deltaY: Float): FloatArray =
        source.copyOf().also { output ->
            var index = 0
            while (index < output.size) {
                output[index] += deltaX
                output[index + 1] += deltaY
                index += 3
            }
        }

    private fun sourceCoordinates(): FloatArray = floatArrayOf(
        0.1f, 0.1f, 0f,
        0.9f, 0.1f, 0f,
        0.1f, 0.9f, 0f,
        0.9f, 0.9f, 0f,
        0.40f, 0.45f, 0f,
        0.60f, 0.45f, 0f,
        0.60f, 0.55f, 0f,
        0.40f, 0.55f, 0f,
        0.43f, 0.48f, 0f,
        0.57f, 0.48f, 0f,
        0.57f, 0.52f, 0f,
        0.43f, 0.52f, 0f,
    )

    private companion object {
        const val FIRST_TIMESTAMP = 1_000_000_000L
        const val FLOW_TIMESTAMP = 1_033_000_000L
        const val CURRENT_TIMESTAMP = 1_066_000_000L
    }
}
