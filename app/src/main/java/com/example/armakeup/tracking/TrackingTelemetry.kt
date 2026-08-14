package com.example.armakeup.tracking

import com.example.armakeup.makeup.LipLandmarkTopology
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Debug-only event sink used to record a deterministic V6 tracking replay. */
interface TrackingTelemetrySink {
    fun recordMeasurement(sample: TrackingMeasurementSample)

    fun recordRender(sample: TrackingRenderSample)
}

sealed interface TrackingTelemetryEvent

data class TrackingMeasurementSample(
    val captureTimestampMs: Long,
    val sensorTimestampNs: Long,
    val deliveryTimestampMs: Long,
    val latencyMs: Long,
    val mlFps: Float,
    val confidence: Float,
    val facePresent: Boolean,
    val predictedOnly: Boolean,
    val rawLandmarks: FloatArray,
    val filteredLandmarks: FloatArray,
    val velocities: FloatArray,
    val rawGeometry: TrackingGeometry,
    val filteredGeometry: TrackingGeometry,
) : TrackingTelemetryEvent

data class TrackingRenderSample(
    val renderTimestampMs: Long,
    val measurementTimestampMs: Long,
    val sensorTimestampNs: Long,
    val predictionSeconds: Float,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val lipVisible: Boolean,
    val outerLipPoints: FloatArray,
    val innerLipPoints: FloatArray,
) : TrackingTelemetryEvent

data class TrackingGeometry(
    val pose: TrackingPose,
    /** Ordered x/y pairs in head-local coordinates, outer contour followed by inner contour. */
    val localLipCoordinates: FloatArray,
) {
    companion object {
        val INVALID = TrackingGeometry(TrackingPose.INVALID, FloatArray(0))
    }
}

data class TrackingPose(
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val rotationRadians: Float,
) {
    val isValid: Boolean
        get() = centerX.isFinite() && centerY.isFinite() &&
            scale.isFinite() && scale > 0f && rotationRadians.isFinite()

    companion object {
        val INVALID = TrackingPose(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    }
}

/**
 * Extracts a compact global-pose/local-deformation decomposition for diagnostics.
 *
 * The pose deliberately uses rigid eye/nose/cheek anchors rather than the centroid of all 478
 * landmarks, which moves when the mouth or brows deform. This is telemetry groundwork for the V6
 * production predictor; it does not yet modify visible geometry.
 */
object TrackingGeometryExtractor {
    private val stableAnchorIndices = intArrayOf(
        33, 133, // left eye corners
        362, 263, // right eye corners
        168, 1, // nose bridge and tip
        234, 454, // lateral cheeks
    )
    private val leftEyeIndices = intArrayOf(33, 133)
    private val rightEyeIndices = intArrayOf(362, 263)
    private val lipIndices = LipLandmarkTopology.outerContour + LipLandmarkTopology.innerContour
    private val maximumRequiredIndex = maxOf(
        stableAnchorIndices.maxOrNull() ?: 0,
        lipIndices.maxOrNull() ?: 0,
    )

    fun extract(coordinates: FloatArray): TrackingGeometry {
        if (
            coordinates.size % LandmarkRenderFrame.COORDINATE_COUNT != 0 ||
            coordinates.size / LandmarkRenderFrame.COORDINATE_COUNT <= maximumRequiredIndex
        ) {
            return TrackingGeometry.INVALID
        }

        val center = meanPoint(coordinates, stableAnchorIndices)
        val leftEye = meanPoint(coordinates, leftEyeIndices)
        val rightEye = meanPoint(coordinates, rightEyeIndices)
        val eyeVectorX = rightEye.first - leftEye.first
        val eyeVectorY = rightEye.second - leftEye.second
        val scale = hypot(eyeVectorX, eyeVectorY)
        if (!scale.isFinite() || scale <= MINIMUM_POSE_SCALE) return TrackingGeometry.INVALID

        val rotation = atan2(eyeVectorY, eyeVectorX)
        val pose = TrackingPose(
            centerX = center.first,
            centerY = center.second,
            scale = scale,
            rotationRadians = rotation,
        )
        val cosRotation = cos(rotation)
        val sinRotation = sin(rotation)
        val localLipCoordinates = FloatArray(lipIndices.size * POINT_COMPONENT_COUNT)
        lipIndices.forEachIndexed { pointIndex, landmarkIndex ->
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            val deltaX = coordinates[coordinateIndex] - pose.centerX
            val deltaY = coordinates[coordinateIndex + 1] - pose.centerY
            val outputIndex = pointIndex * POINT_COMPONENT_COUNT
            localLipCoordinates[outputIndex] =
                (cosRotation * deltaX + sinRotation * deltaY) / scale
            localLipCoordinates[outputIndex + 1] =
                (-sinRotation * deltaX + cosRotation * deltaY) / scale
        }
        return TrackingGeometry(pose, localLipCoordinates)
    }

    private fun meanPoint(coordinates: FloatArray, indices: IntArray): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        indices.forEach { landmarkIndex ->
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            x += coordinates[coordinateIndex]
            y += coordinates[coordinateIndex + 1]
        }
        return x / indices.size to y / indices.size
    }

    private const val POINT_COMPONENT_COUNT = 2
    private const val MINIMUM_POSE_SCALE = 1e-5f
}
