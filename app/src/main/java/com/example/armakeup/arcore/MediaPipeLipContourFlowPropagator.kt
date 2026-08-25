package com.example.armakeup.arcore

import com.example.armakeup.render.VulkanMouthFlowFrame
import com.example.armakeup.tracking.face.FaceCoordinateSpace
import com.example.armakeup.tracking.face.FaceLandmarkSet
import com.example.armakeup.tracking.face.FaceLocalAffineTransform
import com.example.armakeup.tracking.face.FaceObservation
import com.example.armakeup.tracking.face.FaceRegion
import com.example.armakeup.tracking.face.FaceRegionGeometry
import java.util.ArrayDeque
import kotlin.math.hypot

internal data class PropagatedLipGeometry(
    val outer: FaceRegionGeometry,
    val inner: FaceRegionGeometry,
    val semanticTimestampNs: Long,
    val propagatedToTimestampNs: Long,
    val currentTimestampNs: Long,
    val meanFlowConfidence: Float,
)

/**
 * Transports the semantic contour with a low-dimensional motion model.
 *
 * ARCore stable anchors own global motion. Reliable optical-flow samples jointly estimate one
 * robust affine bridge shared by both contours. No landmark receives an independent flow vector,
 * so temporal transport cannot tear or fold the lip polygon.
 */
internal class MediaPipeLipContourFlowPropagator(
    stableAnchorIndices: IntArray,
    outerLipIndices: IntArray,
    innerLipIndices: IntArray,
    private val maximumAnchorResidual: Float,
) {
    private val stableAnchors = stableAnchorIndices.copyOf()
    private val outerIndices = outerLipIndices.copyOf()
    private val innerIndices = innerLipIndices.copyOf()
    private val globalFrames = ArrayDeque<GlobalFrame>()
    private val flowFrames = ArrayDeque<VulkanMouthFlowFrame>()

    init {
        require(stableAnchors.size >= 3)
        require(outerIndices.isNotEmpty() && outerIndices.size == innerIndices.size)
        require(maximumAnchorResidual.isFinite() && maximumAnchorResidual > 0f)
    }

    fun recordGlobal(observation: FaceObservation) {
        val display = observation.displayLandmarks ?: return
        if (!observation.quality.tracking ||
            display.coordinateSpace != FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT
        ) return
        if (globalFrames.peekLast()?.timestampNs?.let { observation.sensorTimestampNs <= it } == true) {
            return
        }
        globalFrames.addLast(GlobalFrame(observation.sensorTimestampNs, display))
        trim(observation.sensorTimestampNs)
    }

    fun recordFlow(flow: VulkanMouthFlowFrame?) {
        flow ?: return
        if (flowFrames.peekLast()?.toSensorTimestampNs?.let {
                flow.toSensorTimestampNs <= it
            } == true
        ) return
        flowFrames.addLast(flow)
        trim(flow.toSensorTimestampNs)
    }

    fun propagate(
        localObservation: FaceObservation?,
        currentGlobalObservation: FaceObservation?,
    ): PropagatedLipGeometry? {
        val local = localObservation ?: return null
        val current = currentGlobalObservation ?: return null
        val localLandmarks = local.imageLandmarks ?: return null
        val currentDisplay = current.displayLandmarks ?: return null
        if (!local.quality.tracking || !current.quality.tracking ||
            localLandmarks.coordinateSpace != FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT ||
            currentDisplay.coordinateSpace != FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT
        ) return null

        val historical = globalFrame(local.sensorTimestampNs) ?: return null
        val localToHistorical = estimateAffine(localLandmarks, historical.display) ?: return null
        if (localToHistorical.normalizedRmsResidual > maximumAnchorResidual) return null
        var outer = mapLandmarks(localLandmarks, outerIndices, localToHistorical)
        var inner = mapLandmarks(localLandmarks, innerIndices, localToHistorical)
        var cursor = local.sensorTimestampNs
        var confidenceSum = 0f
        var flowCount = 0

        while (cursor < current.sensorTimestampNs) {
            val flow = flowFrames.firstOrNull {
                it.fromSensorTimestampNs == cursor &&
                    it.toSensorTimestampNs <= current.sensorTimestampNs
            } ?: break
            val fromGlobal = globalFrame(cursor) ?: return null
            val toGlobal = globalFrame(flow.toSensorTimestampNs) ?: return null
            val globalMotion = estimateAffine(fromGlobal.display, toGlobal.display) ?: return null
            if (globalMotion.normalizedRmsResidual > MAXIMUM_STEP_GLOBAL_AFFINE_RESIDUAL) return null
            val bridge = estimateFlowBridge(outer, inner, flow)
            val bridged = propagateContours(outer, inner, globalMotion, bridge?.transform)
            outer = bridged.outer
            inner = bridged.inner
            confidenceSum += bridge?.meanConfidence ?: 0f
            flowCount++
            cursor = flow.toSensorTimestampNs
        }

        val remainingAgeNs = current.sensorTimestampNs - cursor
        if (remainingAgeNs !in 0L..MAXIMUM_GLOBAL_COMPENSATION_AGE_NS) return null
        if (cursor != current.sensorTimestampNs) {
            val propagatedGlobal = globalFrame(cursor) ?: return null
            val toCurrent = estimateAffine(propagatedGlobal.display, currentDisplay) ?: return null
            if (toCurrent.normalizedRmsResidual > MAXIMUM_FINAL_AFFINE_RESIDUAL) return null
            outer = mapPacked(outer, toCurrent)
            inner = mapPacked(inner, toCurrent)
        }
        val reanchored = verticallyReanchorLip(outer, inner, currentDisplay)
        outer = reanchored.outer
        inner = reanchored.inner
        if (!plausible(outer) || !plausible(inner)) return null
        return PropagatedLipGeometry(
            outer = FaceRegionGeometry.of(FaceRegion.LIPS_OUTER, outer),
            inner = FaceRegionGeometry.of(FaceRegion.LIPS_INNER, inner),
            semanticTimestampNs = local.sensorTimestampNs,
            propagatedToTimestampNs = cursor,
            currentTimestampNs = current.sensorTimestampNs,
            meanFlowConfidence = if (flowCount == 0) 0f else confidenceSum / flowCount,
        )
    }

    fun reset() {
        globalFrames.clear()
        flowFrames.clear()
    }

    private fun globalFrame(timestampNs: Long): GlobalFrame? =
        globalFrames.firstOrNull { it.timestampNs == timestampNs }

    private fun estimateAffine(
        source: FaceLandmarkSet,
        target: FaceLandmarkSet,
    ): FaceLocalAffineTransform? {
        val maximumIndex = stableAnchors.maxOrNull() ?: return null
        if (source.pointCount <= maximumIndex || target.pointCount <= maximumIndex) return null
        val sourcePoints = FloatArray(stableAnchors.size * 2)
        val targetPoints = FloatArray(sourcePoints.size)
        stableAnchors.forEachIndexed { point, landmark ->
            val offset = point * 2
            sourcePoints[offset] = source.x(landmark)
            sourcePoints[offset + 1] = source.y(landmark)
            targetPoints[offset] = target.x(landmark)
            targetPoints[offset + 1] = target.y(landmark)
        }
        return FaceLocalAffineTransform.estimate(sourcePoints, targetPoints)
    }

    /**
     * Removes the systematic vertical offset between MediaPipe and ARCore face meshes.
     *
     * Correction happens after temporal transport, so it cannot move sampling points away from
     * their source-frame pixels. Invalid ARCore lip geometry leaves the propagated contour intact.
     */
    private fun verticallyReanchorLip(
        outer: FloatArray,
        inner: FloatArray,
        target: FaceLandmarkSet,
    ): PropagatedContours {
        val lipIndices = outerIndices + innerIndices
        val maximumIndex = lipIndices.maxOrNull()
            ?: return PropagatedContours(outer, inner)
        if (target.pointCount <= maximumIndex) return PropagatedContours(outer, inner)
        var mappedCenterY = 0f
        var targetCenterY = 0f
        var offset = 1
        while (offset < outer.size) {
            mappedCenterY += outer[offset]
            offset += 2
        }
        offset = 1
        while (offset < inner.size) {
            mappedCenterY += inner[offset]
            offset += 2
        }
        lipIndices.forEach { landmark ->
            targetCenterY += target.y(landmark)
        }
        mappedCenterY /= lipIndices.size
        targetCenterY /= lipIndices.size
        val correction = (targetCenterY - mappedCenterY)
            .coerceIn(-MAXIMUM_VERTICAL_LIP_REANCHOR, MAXIMUM_VERTICAL_LIP_REANCHOR)
        if (!correction.isFinite()) return PropagatedContours(outer, inner)
        fun shifted(source: FloatArray) = source.copyOf().also { coordinates ->
            var y = 1
            while (y < coordinates.size) {
                coordinates[y] += correction
                y += 2
            }
        }
        val corrected = PropagatedContours(shifted(outer), shifted(inner))
        return if (plausible(corrected.outer) && plausible(corrected.inner)) {
            corrected
        } else {
            PropagatedContours(outer, inner)
        }
    }

    private fun estimateFlowBridge(
        outer: FloatArray,
        inner: FloatArray,
        flow: VulkanMouthFlowFrame,
    ): FlowBridge? {
        val packed = outer + inner
        val source = FloatArray(packed.size)
        val target = FloatArray(packed.size)
        val confidence = FloatArray(packed.size / 2)
        var accepted = 0
        var offset = 0
        while (offset < packed.size) {
            val x = packed[offset]
            val y = packed[offset + 1]
            val sample = flow.sample(x, y)
            if (sample != null &&
                sample.confidence >= MINIMUM_FLOW_CONFIDENCE &&
                sample.residual <= MAXIMUM_FLOW_PHOTOMETRIC_RESIDUAL
            ) {
                source[accepted * 2] = x
                source[accepted * 2 + 1] = y
                target[accepted * 2] = x + sample.deltaX
                target[accepted * 2 + 1] = y + sample.deltaY
                confidence[accepted] = sample.confidence
                accepted++
            }
            offset += 2
        }
        if (accepted < MINIMUM_AFFINE_FLOW_POINTS) return null
        val initial = FaceLocalAffineTransform.estimate(
            source.copyOf(accepted * 2),
            target.copyOf(accepted * 2),
        ) ?: return null

        val inlierSource = FloatArray(accepted * 2)
        val inlierTarget = FloatArray(accepted * 2)
        var inlierCount = 0
        var confidenceSum = 0f
        repeat(accepted) { point ->
            val index = point * 2
            val x = source[index]
            val y = source[index + 1]
            val error = hypot(
                initial.mapX(x, y) - target[index],
                initial.mapY(x, y) - target[index + 1],
            )
            if (error <= MAXIMUM_FLOW_AFFINE_POINT_RESIDUAL) {
                inlierSource[inlierCount * 2] = x
                inlierSource[inlierCount * 2 + 1] = y
                inlierTarget[inlierCount * 2] = target[index]
                inlierTarget[inlierCount * 2 + 1] = target[index + 1]
                confidenceSum += confidence[point]
                inlierCount++
            }
        }
        if (inlierCount < MINIMUM_AFFINE_FLOW_POINTS) return null
        val refined = FaceLocalAffineTransform.estimate(
            inlierSource.copyOf(inlierCount * 2),
            inlierTarget.copyOf(inlierCount * 2),
        ) ?: return null
        if (refined.normalizedRmsResidual > MAXIMUM_FLOW_AFFINE_RMS_RESIDUAL) return null
        return FlowBridge(refined, confidenceSum / inlierCount)
    }

    private fun propagateContours(
        outer: FloatArray,
        inner: FloatArray,
        globalMotion: FaceLocalAffineTransform,
        flowMotion: FaceLocalAffineTransform?,
    ): PropagatedContours {
        val combined = outer + inner
        var maximumRawResidual = 0f
        if (flowMotion != null) {
            var offset = 0
            while (offset < combined.size) {
                val x = combined[offset]
                val y = combined[offset + 1]
                maximumRawResidual = maxOf(
                    maximumRawResidual,
                    hypot(
                        flowMotion.mapX(x, y) - globalMotion.mapX(x, y),
                        flowMotion.mapY(x, y) - globalMotion.mapY(x, y),
                    ),
                )
                offset += 2
            }
        }
        val localScale = if (
            flowMotion == null ||
            !maximumRawResidual.isFinite() ||
            maximumRawResidual > MAXIMUM_RAW_BRIDGE_RESIDUAL
        ) {
            0f
        } else if (maximumRawResidual > MAXIMUM_APPLIED_BRIDGE_RESIDUAL) {
            MAXIMUM_APPLIED_BRIDGE_RESIDUAL / maximumRawResidual
        } else {
            1f
        }
        fun propagate(source: FloatArray): FloatArray = FloatArray(source.size).also { output ->
            var offset = 0
            while (offset < source.size) {
                val x = source[offset]
                val y = source[offset + 1]
                val globalX = globalMotion.mapX(x, y)
                val globalY = globalMotion.mapY(x, y)
                output[offset] = globalX + LOCAL_BRIDGE_GAIN * localScale *
                    ((flowMotion?.mapX(x, y) ?: globalX) - globalX)
                output[offset + 1] = globalY + LOCAL_BRIDGE_GAIN * localScale *
                    ((flowMotion?.mapY(x, y) ?: globalY) - globalY)
                offset += 2
            }
        }
        return PropagatedContours(propagate(outer), propagate(inner))
    }

    private fun mapLandmarks(
        source: FaceLandmarkSet,
        indices: IntArray,
        transform: FaceLocalAffineTransform,
    ): FloatArray = FloatArray(indices.size * 2).also { output ->
        indices.forEachIndexed { point, landmark ->
            val offset = point * 2
            val x = source.x(landmark)
            val y = source.y(landmark)
            output[offset] = transform.mapX(x, y)
            output[offset + 1] = transform.mapY(x, y)
        }
    }

    private fun mapPacked(source: FloatArray, transform: FaceLocalAffineTransform): FloatArray =
        FloatArray(source.size).also { output ->
            var offset = 0
            while (offset < source.size) {
                output[offset] = transform.mapX(source[offset], source[offset + 1])
                output[offset + 1] = transform.mapY(source[offset], source[offset + 1])
                offset += 2
            }
        }

    private fun plausible(contour: FloatArray): Boolean {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var offset = 0
        while (offset < contour.size) {
            val x = contour[offset]
            val y = contour[offset + 1]
            if (!x.isFinite() || !y.isFinite() ||
                x !in DISPLAY_MARGIN..(1f - DISPLAY_MARGIN) ||
                y !in DISPLAY_MARGIN..(1f - DISPLAY_MARGIN)
            ) return false
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
            offset += 2
        }
        return maxX - minX in MINIMUM_EXTENT..MAXIMUM_EXTENT &&
            maxY - minY in MINIMUM_EXTENT..MAXIMUM_EXTENT
    }

    private fun trim(referenceTimestampNs: Long) {
        val oldest = (referenceTimestampNs - HISTORY_DURATION_NS).coerceAtLeast(0L)
        while (globalFrames.peekFirst()?.timestampNs?.let { it < oldest } == true) {
            globalFrames.removeFirst()
        }
        while (flowFrames.peekFirst()?.toSensorTimestampNs?.let { it < oldest } == true) {
            flowFrames.removeFirst()
        }
    }

    private data class GlobalFrame(val timestampNs: Long, val display: FaceLandmarkSet)
    private data class FlowBridge(val transform: FaceLocalAffineTransform, val meanConfidence: Float)
    private data class PropagatedContours(val outer: FloatArray, val inner: FloatArray)

    private companion object {
        const val HISTORY_DURATION_NS = 800_000_000L
        const val MAXIMUM_GLOBAL_COMPENSATION_AGE_NS = 80_000_000L
        const val MAXIMUM_STEP_GLOBAL_AFFINE_RESIDUAL = 0.02f
        const val MAXIMUM_FINAL_AFFINE_RESIDUAL = 0.02f
        const val MINIMUM_FLOW_CONFIDENCE = 0.18f
        const val MAXIMUM_FLOW_PHOTOMETRIC_RESIDUAL = 0.08f
        const val MINIMUM_AFFINE_FLOW_POINTS = 12
        const val MAXIMUM_FLOW_AFFINE_POINT_RESIDUAL = 0.006f
        const val MAXIMUM_FLOW_AFFINE_RMS_RESIDUAL = 0.004f
        const val MAXIMUM_RAW_BRIDGE_RESIDUAL = 0.025f
        const val MAXIMUM_APPLIED_BRIDGE_RESIDUAL = 0.004f
        const val LOCAL_BRIDGE_GAIN = 0.55f
        const val MAXIMUM_VERTICAL_LIP_REANCHOR = 0.025f
        const val DISPLAY_MARGIN = -0.08f
        const val MINIMUM_EXTENT = 0.002f
        const val MAXIMUM_EXTENT = 0.7f
    }
}
