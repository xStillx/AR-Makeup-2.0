package com.example.armakeup.arcore

import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.tracking.FaceObservation
import com.example.armakeup.tracking.LipContourObservation
import com.example.armakeup.tracking.LipSemanticState
import com.example.armakeup.tracking.NormalizedContour2D
import kotlin.math.hypot

/** Converts MediaPipe's packed landmarks into the model-independent face contract. */
internal object MediaPipeFaceObservationAdapter {

    fun create(
        coordinates: FloatArray,
        sensorTimestampNs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
    ): FaceObservation? {
        if (sensorTimestampNs <= 0L || sourceWidth <= 0 || sourceHeight <= 0) return null
        val maximumIndex = maxOf(
            LipLandmarkTopology.outerContour.maxOrNull() ?: return null,
            LipLandmarkTopology.innerContour.maxOrNull() ?: return null,
        )
        if (coordinates.size / LANDMARK_COMPONENT_COUNT <= maximumIndex) return null

        val outer = extractContour(coordinates, LipLandmarkTopology.outerContour) ?: return null
        val inner = extractContour(coordinates, LipLandmarkTopology.innerContour) ?: return null
        val geometry = evaluateGeometry(outer, inner) ?: return null
        val semanticConfidence = (
            geometry.confidence * (0.78f + 0.22f * geometry.innerContainment)
        ).coerceIn(0f, 1f)

        return FaceObservation(
            sensorTimestampNs = sensorTimestampNs,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            mirrorHorizontal = true,
            lips = LipContourObservation(
                outer = NormalizedContour2D(outer),
                inner = NormalizedContour2D(inner),
                geometryConfidence = geometry.confidence,
                mouthOpenness = geometry.mouthOpenness,
                semantics = LipSemanticState(
                    lipConfidence = semanticConfidence,
                    mouthTeethExclusionConfidence = geometry.innerContainment,
                    edgeRefinementStrength = (semanticConfidence * 0.72f).coerceIn(0f, 1f),
                ),
            ),
        )
    }

    private fun extractContour(coordinates: FloatArray, topology: IntArray): FloatArray? {
        val output = FloatArray(topology.size * POINT_COMPONENT_COUNT)
        topology.forEachIndexed { outputPoint, landmarkIndex ->
            val source = landmarkIndex * LANDMARK_COMPONENT_COUNT
            val x = coordinates[source]
            val y = coordinates[source + 1]
            if (!x.isFinite() || !y.isFinite()) return null
            if (x !in NORMALIZED_INPUT_RANGE || y !in NORMALIZED_INPUT_RANGE) return null
            val target = outputPoint * POINT_COMPONENT_COUNT
            output[target] = x
            output[target + 1] = y
        }
        return output
    }

    private fun evaluateGeometry(outer: FloatArray, inner: FloatArray): Geometry? {
        val width = pointDistance(outer, LEFT_CORNER_INDEX, RIGHT_CORNER_INDEX)
        if (width <= MINIMUM_LIP_WIDTH || !width.isFinite()) return null
        val outerHeight = pointDistance(outer, LOWER_CENTER_INDEX, UPPER_CENTER_INDEX)
        val innerHeight = pointDistance(inner, LOWER_CENTER_INDEX, UPPER_CENTER_INDEX)
        val aspectRatio = outerHeight / width
        val openingRatio = innerHeight / width

        val widthScore = bandScore(width, 0.035f, 0.055f, 0.58f, 0.78f)
        val aspectScore = bandScore(aspectRatio, 0.055f, 0.10f, 0.62f, 0.82f)
        val openingScore = bandScore(openingRatio, 0f, 0f, 0.58f, 0.72f)
        val innerContainment = containmentScore(outer, inner, width)
        val confidence = (
            widthScore * 0.20f +
                aspectScore * 0.28f +
                openingScore * 0.18f +
                innerContainment * 0.34f
        ).coerceIn(0f, 1f)
        if (confidence < MINIMUM_GEOMETRY_CONFIDENCE) return null

        return Geometry(
            confidence = confidence,
            innerContainment = innerContainment,
            mouthOpenness = (openingRatio / FULLY_OPEN_RATIO).coerceIn(0f, 1f),
        )
    }

    private fun containmentScore(outer: FloatArray, inner: FloatArray, width: Float): Float {
        var minimumX = Float.POSITIVE_INFINITY
        var maximumX = Float.NEGATIVE_INFINITY
        var minimumY = Float.POSITIVE_INFINITY
        var maximumY = Float.NEGATIVE_INFINITY
        var index = 0
        while (index < outer.size) {
            minimumX = minOf(minimumX, outer[index])
            maximumX = maxOf(maximumX, outer[index])
            minimumY = minOf(minimumY, outer[index + 1])
            maximumY = maxOf(maximumY, outer[index + 1])
            index += POINT_COMPONENT_COUNT
        }
        val tolerance = width * CONTAINMENT_TOLERANCE_FRACTION
        var contained = 0
        index = 0
        while (index < inner.size) {
            if (
                inner[index] in (minimumX - tolerance)..(maximumX + tolerance) &&
                inner[index + 1] in (minimumY - tolerance)..(maximumY + tolerance)
            ) {
                contained++
            }
            index += POINT_COMPONENT_COUNT
        }
        return contained.toFloat() / (inner.size / POINT_COMPONENT_COUNT)
    }

    private fun pointDistance(points: FloatArray, first: Int, second: Int): Float {
        val firstOffset = first * POINT_COMPONENT_COUNT
        val secondOffset = second * POINT_COMPONENT_COUNT
        return hypot(
            points[secondOffset] - points[firstOffset],
            points[secondOffset + 1] - points[firstOffset + 1],
        )
    }

    private fun bandScore(
        value: Float,
        minimumReject: Float,
        minimumFull: Float,
        maximumFull: Float,
        maximumReject: Float,
    ): Float {
        val lower = if (minimumFull <= minimumReject) {
            1f
        } else {
            ((value - minimumReject) / (minimumFull - minimumReject)).coerceIn(0f, 1f)
        }
        val upper = if (maximumReject <= maximumFull) {
            1f
        } else {
            ((maximumReject - value) / (maximumReject - maximumFull)).coerceIn(0f, 1f)
        }
        return minOf(lower, upper)
    }

    private data class Geometry(
        val confidence: Float,
        val innerContainment: Float,
        val mouthOpenness: Float,
    )

    private val NORMALIZED_INPUT_RANGE = -0.20f..1.20f

    private const val LANDMARK_COMPONENT_COUNT = 3
    private const val POINT_COMPONENT_COUNT = 2
    private const val LEFT_CORNER_INDEX = 0
    private const val LOWER_CENTER_INDEX = 5
    private const val RIGHT_CORNER_INDEX = 10
    private const val UPPER_CENTER_INDEX = 15
    private const val MINIMUM_LIP_WIDTH = 0.025f
    private const val MINIMUM_GEOMETRY_CONFIDENCE = 0.30f
    private const val CONTAINMENT_TOLERANCE_FRACTION = 0.08f
    private const val FULLY_OPEN_RATIO = 0.42f
}
