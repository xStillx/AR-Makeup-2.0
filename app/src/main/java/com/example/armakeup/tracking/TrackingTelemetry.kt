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
    val captureIntervalMs: Long = -1L,
    val frameQuality: TrackingFrameQuality = TrackingFrameQuality.UNKNOWN,
    val poseFitQuality: TrackingPoseFitQuality = TrackingPoseFitQuality.UNKNOWN,
    val deviceState: TrackingDeviceState = TrackingDeviceState.UNKNOWN,
    val pipelineTiming: TrackingPipelineTiming = TrackingPipelineTiming.UNKNOWN,
    /** MediaPipe canonical-face 4x4 transform, recorded in shadow mode only. */
    val facialTransformationMatrix: FloatArray = FloatArray(0),
) : TrackingTelemetryEvent

/**
 * Monotonic timestamps and CPU stage durations used to decompose capture-to-result latency.
 * Unknown values keep legacy telemetry readable without inventing zero-duration stages.
 */
data class TrackingPipelineTiming(
    val analysisStartTimestampMs: Long,
    val submitTimestampMs: Long,
    val callbackTimestampMs: Long,
    val callbackHandlerStartTimestampMs: Long,
    val rgbaCopyDurationMs: Float,
    val qualityAnalysisDurationMs: Float,
    val resultProcessingDurationMs: Float,
) {
    companion object {
        val UNKNOWN = TrackingPipelineTiming(
            analysisStartTimestampMs = -1L,
            submitTimestampMs = -1L,
            callbackTimestampMs = -1L,
            callbackHandlerStartTimestampMs = -1L,
            rgbaCopyDurationMs = Float.NaN,
            qualityAnalysisDurationMs = Float.NaN,
            resultProcessingDurationMs = Float.NaN,
        )
    }
}

data class TrackingFrameQuality(
    val meanLuma: Float,
    val lumaStandardDeviation: Float,
    val meanGradient: Float,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val frameDurationNs: Long,
    val rollingShutterSkewNs: Long,
    val aeState: Int,
) {
    companion object {
        val UNKNOWN = TrackingFrameQuality(
            meanLuma = Float.NaN,
            lumaStandardDeviation = Float.NaN,
            meanGradient = Float.NaN,
            exposureTimeNs = -1L,
            sensitivityIso = -1,
            frameDurationNs = -1L,
            rollingShutterSkewNs = -1L,
            aeState = -1,
        )
    }
}

data class TrackingPoseFitQuality(
    val normalizedRmsResidual: Float,
    val inlierFraction: Float,
    val quality: Float,
) {
    val isValid: Boolean
        get() = normalizedRmsResidual.isFinite() && inlierFraction.isFinite() &&
            quality.isFinite()

    companion object {
        val UNKNOWN = TrackingPoseFitQuality(Float.NaN, Float.NaN, Float.NaN)
    }
}

data class TrackingDeviceState(
    val thermalStatus: Int,
    val batteryTemperatureCelsius: Float,
) {
    companion object {
        val UNKNOWN = TrackingDeviceState(
            thermalStatus = -1,
            batteryTemperatureCelsius = Float.NaN,
        )
    }
}

/**
 * CPU-observable render timeline markers, expressed in the elapsed-realtime nanosecond clock.
 * Choreographer's monotonic frame time is normalized into that clock at callback entry.
 *
 * The camera marker means that a buffer was handed to Filament, and the geometry marker means
 * that Filament accepted the vertex-buffer upload command. Neither marker proves GPU completion.
 * Filament's Java API does not currently expose an actual presentation timestamp, so that field
 * deliberately remains [UNKNOWN_TIMESTAMP_NS] until a native/FrameTimeline source is added.
 */
data class TrackingRenderTiming(
    val vsyncTimestampNs: Long,
    val renderStartTimestampNs: Long,
    val cameraFrameSelectedTimestampNs: Long,
    val cameraFrameSensorTimestampNs: Long,
    val geometryUploadAcceptedTimestampNs: Long,
    val renderSubmitTimestampNs: Long,
    val presentationTimestampNs: Long,
) {
    companion object {
        const val UNKNOWN_TIMESTAMP_NS = -1L

        val UNKNOWN = TrackingRenderTiming(
            vsyncTimestampNs = UNKNOWN_TIMESTAMP_NS,
            renderStartTimestampNs = UNKNOWN_TIMESTAMP_NS,
            cameraFrameSelectedTimestampNs = UNKNOWN_TIMESTAMP_NS,
            cameraFrameSensorTimestampNs = UNKNOWN_TIMESTAMP_NS,
            geometryUploadAcceptedTimestampNs = UNKNOWN_TIMESTAMP_NS,
            renderSubmitTimestampNs = UNKNOWN_TIMESTAMP_NS,
            presentationTimestampNs = UNKNOWN_TIMESTAMP_NS,
        )
    }
}

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
    val lipstickFinish: String = UNKNOWN_LIPSTICK_FINISH,
    val materialCameraCoherence: Float = Float.NaN,
    val materialMotionSpeed: Float = Float.NaN,
    val materialTemporalMismatchMs: Float = Float.NaN,
    val frameSubmissionCpuMs: Float = Float.NaN,
    val filamentFrameRendered: Boolean = true,
    val gyroscopeApplied: Boolean = false,
    val gyroscopeIntervalMs: Float = Float.NaN,
    val gyroscopeRotationX: Float = 0f,
    val gyroscopeRotationY: Float = 0f,
    val gyroscopeRotationZ: Float = 0f,
    val gyroscopeTranslationX: Float = 0f,
    val gyroscopeTranslationY: Float = 0f,
    val gyroscopeRollRadians: Float = 0f,
    val cameraMotionPredictionSeconds: Float = Float.NaN,
    val globalPredictionCoverage: Float = Float.NaN,
    val renderTiming: TrackingRenderTiming = TrackingRenderTiming.UNKNOWN,
) : TrackingTelemetryEvent

private const val UNKNOWN_LIPSTICK_FINISH = "UNKNOWN"

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
 * landmarks, which moves when the mouth or brows deform. The same pose definition is now shared
 * by V6 telemetry and the visible stateful predictor.
 */
object TrackingGeometryExtractor {
    internal val stableAnchorIndices = intArrayOf(
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
    private val maximumStableAnchorIndex = stableAnchorIndices.maxOrNull() ?: 0

    fun extract(coordinates: FloatArray): TrackingGeometry {
        if (
            coordinates.size % LandmarkRenderFrame.COORDINATE_COUNT != 0 ||
            coordinates.size / LandmarkRenderFrame.COORDINATE_COUNT <= maximumRequiredIndex
        ) {
            return TrackingGeometry.INVALID
        }

        val pose = extractPose(coordinates)
        if (!pose.isValid) return TrackingGeometry.INVALID
        val cosRotation = cos(pose.rotationRadians)
        val sinRotation = sin(pose.rotationRadians)
        val localLipCoordinates = FloatArray(lipIndices.size * POINT_COMPONENT_COUNT)
        lipIndices.forEachIndexed { pointIndex, landmarkIndex ->
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            val deltaX = coordinates[coordinateIndex] - pose.centerX
            val deltaY = coordinates[coordinateIndex + 1] - pose.centerY
            val outputIndex = pointIndex * POINT_COMPONENT_COUNT
            localLipCoordinates[outputIndex] =
                (cosRotation * deltaX + sinRotation * deltaY) / pose.scale
            localLipCoordinates[outputIndex + 1] =
                (-sinRotation * deltaX + cosRotation * deltaY) / pose.scale
        }
        return TrackingGeometry(pose, localLipCoordinates)
    }

    /** Allocation-free pose-only path shared by telemetry and the temporal predictor. */
    fun extractPose(coordinates: FloatArray): TrackingPose {
        if (
            coordinates.size % LandmarkRenderFrame.COORDINATE_COUNT != 0 ||
            coordinates.size / LandmarkRenderFrame.COORDINATE_COUNT <= maximumStableAnchorIndex
        ) {
            return TrackingPose.INVALID
        }
        val centerX = meanCoordinate(coordinates, stableAnchorIndices, X_OFFSET)
        val centerY = meanCoordinate(coordinates, stableAnchorIndices, Y_OFFSET)
        val leftEyeX = meanCoordinate(coordinates, leftEyeIndices, X_OFFSET)
        val leftEyeY = meanCoordinate(coordinates, leftEyeIndices, Y_OFFSET)
        val rightEyeX = meanCoordinate(coordinates, rightEyeIndices, X_OFFSET)
        val rightEyeY = meanCoordinate(coordinates, rightEyeIndices, Y_OFFSET)
        val eyeVectorX = rightEyeX - leftEyeX
        val eyeVectorY = rightEyeY - leftEyeY
        val scale = hypot(eyeVectorX, eyeVectorY)
        if (!scale.isFinite() || scale <= MINIMUM_POSE_SCALE) return TrackingPose.INVALID

        return TrackingPose(
            centerX = centerX,
            centerY = centerY,
            scale = scale,
            rotationRadians = atan2(eyeVectorY, eyeVectorX),
        )
    }

    private fun meanCoordinate(
        coordinates: FloatArray,
        indices: IntArray,
        coordinateOffset: Int,
    ): Float {
        var value = 0f
        indices.forEach { landmarkIndex ->
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            value += coordinates[coordinateIndex + coordinateOffset]
        }
        return value / indices.size
    }

    private const val POINT_COMPONENT_COUNT = 2
    private const val X_OFFSET = 0
    private const val Y_OFFSET = 1
    private const val MINIMUM_POSE_SCALE = 1e-5f
}
