package com.example.armakeup.tracking.face

/** The semantic responsibility of one backend observation. */
enum class FaceObservationRole {
    GLOBAL_POSE,
    LOCAL_DEFORMATION,
    COMPLETE,
}

/** Coordinate systems allowed at the model-independent tracking boundary. */
enum class FaceCoordinateSpace {
    FACE_LOCAL_METERS,
    NORMALIZED_IMAGE_TOP_LEFT,
    NORMALIZED_DISPLAY_TOP_LEFT,
}

data class FaceTopologyDescriptor(
    val id: String,
    val version: Int,
    val pointCount: Int,
) {
    init {
        require(id.isNotBlank())
        require(version > 0)
        require(pointCount > 0)
    }
}

object FaceTopologies {
    val ARCORE_AUGMENTED_FACE_468 = FaceTopologyDescriptor(
        id = "arcore-augmented-face",
        version = 1,
        pointCount = 468,
    )
    val MEDIAPIPE_FACE_LANDMARKER_478 = FaceTopologyDescriptor(
        id = "mediapipe-face-landmarker",
        version = 1,
        pointCount = 478,
    )
}

/** Immutable packed xyz landmarks. Input storage is never exposed to consumers. */
class FaceLandmarkSet private constructor(
    val topology: FaceTopologyDescriptor,
    val coordinateSpace: FaceCoordinateSpace,
    private val packedCoordinates: FloatArray,
) {
    val pointCount: Int
        get() = topology.pointCount

    init {
        require(packedCoordinates.size == pointCount * COMPONENT_COUNT)
        require(packedCoordinates.all { it.isFinite() })
    }

    fun x(index: Int): Float = packedCoordinates[coordinateIndex(index)]

    fun y(index: Int): Float = packedCoordinates[coordinateIndex(index) + 1]

    fun z(index: Int): Float = packedCoordinates[coordinateIndex(index) + 2]

    fun packedCopy(): FloatArray = packedCoordinates.copyOf()

    private fun coordinateIndex(index: Int): Int {
        require(index in 0 until pointCount)
        return index * COMPONENT_COUNT
    }

    override fun equals(other: Any?): Boolean = other is FaceLandmarkSet &&
        topology == other.topology &&
        coordinateSpace == other.coordinateSpace &&
        packedCoordinates.contentEquals(other.packedCoordinates)

    override fun hashCode(): Int = 31 * (31 * topology.hashCode() + coordinateSpace.hashCode()) +
        packedCoordinates.contentHashCode()

    companion object {
        const val COMPONENT_COUNT = 3

        fun of(
            topology: FaceTopologyDescriptor,
            coordinateSpace: FaceCoordinateSpace,
            packedCoordinates: FloatArray,
        ): FaceLandmarkSet = FaceLandmarkSet(
            topology = topology,
            coordinateSpace = coordinateSpace,
            packedCoordinates = packedCoordinates.copyOf(),
        )

        internal fun takeOwnership(
            topology: FaceTopologyDescriptor,
            coordinateSpace: FaceCoordinateSpace,
            packedCoordinates: FloatArray,
        ): FaceLandmarkSet = FaceLandmarkSet(topology, coordinateSpace, packedCoordinates)
    }
}

/** Immutable column-major 4x4 matrix. */
class FaceMatrix4 private constructor(private val values: FloatArray) {
    init {
        require(values.size == ELEMENT_COUNT)
        require(values.all { it.isFinite() })
    }

    operator fun get(index: Int): Float = values[index]

    fun packedCopy(): FloatArray = values.copyOf()

    override fun equals(other: Any?): Boolean =
        other is FaceMatrix4 && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        const val ELEMENT_COUNT = 16

        fun columnMajor(values: FloatArray): FaceMatrix4 = FaceMatrix4(values.copyOf())
    }
}

data class FacePose(
    val faceToWorld: FaceMatrix4,
)

data class FaceCameraFrameMetadata(
    val sensorTimestampNs: Long,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val displayRotationDegrees: Int,
    val analysisImageRotationDegrees: Int,
    val frontCameraMirrored: Boolean,
    val clipFromCamera: FaceMatrix4,
    val cameraFromWorld: FaceMatrix4,
) {
    init {
        require(sensorTimestampNs >= 0L)
        require(viewportWidth > 0)
        require(viewportHeight > 0)
        require(displayRotationDegrees in DISPLAY_ROTATIONS)
        require(analysisImageRotationDegrees in DISPLAY_ROTATIONS)
    }

    private companion object {
        val DISPLAY_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

data class FaceObservationQuality(
    val tracking: Boolean,
    val trackingConfidence: Float? = null,
    val visibleFraction: Float? = null,
    val fitResidualNormalized: Float? = null,
) {
    init {
        require(trackingConfidence == null || trackingConfidence in 0f..1f)
        require(visibleFraction == null || visibleFraction in 0f..1f)
        require(
            fitResidualNormalized == null ||
                (fitResidualNormalized.isFinite() && fitResidualNormalized >= 0f),
        )
    }
}

data class FaceObservationDiagnostics(
    val resultTimestampNs: Long,
    val inferenceDurationMs: Float = 0f,
    val conversionDurationMs: Float = 0f,
    val smoothedFps: Float = 0f,
) {
    init {
        require(resultTimestampNs >= 0L)
        require(inferenceDurationMs.isFinite() && inferenceDurationMs >= 0f)
        require(conversionDurationMs.isFinite() && conversionDurationMs >= 0f)
        require(smoothedFps.isFinite() && smoothedFps >= 0f)
    }
}

/**
 * One immutable backend measurement at one camera timestamp.
 *
 * ARCore-specific and MediaPipe-specific result classes stop before this boundary. A backend may
 * provide only the landmark representation relevant to its role; the composer validates the
 * required fields before producing a render state.
 */
data class FaceObservation(
    val backendId: String,
    val role: FaceObservationRole,
    val sensorTimestampNs: Long,
    val topology: FaceTopologyDescriptor,
    val canonicalLandmarks: FaceLandmarkSet? = null,
    val imageLandmarks: FaceLandmarkSet? = null,
    val displayLandmarks: FaceLandmarkSet? = null,
    val pose: FacePose? = null,
    val cameraFrame: FaceCameraFrameMetadata? = null,
    val quality: FaceObservationQuality,
    val diagnostics: FaceObservationDiagnostics? = null,
) {
    init {
        require(backendId.isNotBlank())
        require(sensorTimestampNs >= 0L)
        require(canonicalLandmarks != null || imageLandmarks != null || displayLandmarks != null)
        listOfNotNull(canonicalLandmarks, imageLandmarks, displayLandmarks).forEach { landmarks ->
            require(landmarks.topology == topology)
        }
        cameraFrame?.let { require(it.sensorTimestampNs == sensorTimestampNs) }
    }
}

/** Replaceable source of model-independent observations. */
interface FaceTrackingBackend : AutoCloseable {
    val backendId: String
    val role: FaceObservationRole
    val topology: FaceTopologyDescriptor

    fun latestObservation(): FaceObservation?
}

enum class FaceRegion {
    LIPS_OUTER,
    LIPS_INNER,
    LEFT_EYE,
    RIGHT_EYE,
    LEFT_CHEEK,
    RIGHT_CHEEK,
}

data class NormalizedFacePoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite())
        require(y.isFinite())
    }
}

/** Immutable normalized-display xy geometry for one semantic region. */
class FaceRegionGeometry private constructor(
    val region: FaceRegion,
    private val packedCoordinates: FloatArray,
) {
    val pointCount: Int
        get() = packedCoordinates.size / COMPONENT_COUNT

    init {
        require(packedCoordinates.isNotEmpty())
        require(packedCoordinates.size % COMPONENT_COUNT == 0)
        require(packedCoordinates.all { it.isFinite() })
    }

    fun x(index: Int): Float = packedCoordinates[coordinateIndex(index)]

    fun y(index: Int): Float = packedCoordinates[coordinateIndex(index) + 1]

    fun packedCopy(): FloatArray = packedCoordinates.copyOf()

    private fun coordinateIndex(index: Int): Int {
        require(index in 0 until pointCount)
        return index * COMPONENT_COUNT
    }

    companion object {
        const val COMPONENT_COUNT = 2

        fun of(region: FaceRegion, packedCoordinates: FloatArray): FaceRegionGeometry =
            FaceRegionGeometry(region, packedCoordinates.copyOf())

        internal fun takeOwnership(
            region: FaceRegion,
            packedCoordinates: FloatArray,
        ): FaceRegionGeometry = FaceRegionGeometry(region, packedCoordinates)
    }
}

data class FullFaceAttachmentQuality(
    val localDeformationApplied: Boolean,
    val localObservationAgeNs: Long? = null,
    val affineFitResidualNormalized: Float? = null,
) {
    init {
        require(localObservationAgeNs == null || localObservationAgeNs >= 0L)
        require(
            affineFitResidualNormalized == null ||
                (affineFitResidualNormalized.isFinite() && affineFitResidualNormalized >= 0f),
        )
    }
}

/**
 * Renderer-facing face state composed for one currently displayed camera frame.
 *
 * Product renderers consume this type rather than ARCore or MediaPipe result objects.
 */
class FullFaceRenderState(
    val renderTimestampNs: Long,
    val cameraSensorTimestampNs: Long,
    val localObservationTimestampNs: Long?,
    val globalTopology: FaceTopologyDescriptor,
    val globalPose: FacePose,
    val cameraFrame: FaceCameraFrameMetadata,
    val canonicalLandmarks: FaceLandmarkSet,
    val displayLandmarks: FaceLandmarkSet,
    val lipAnchor: NormalizedFacePoint?,
    regions: Map<FaceRegion, FaceRegionGeometry>,
    val attachmentQuality: FullFaceAttachmentQuality,
    val localDiagnostics: FaceObservationDiagnostics?,
) {
    val regions: Map<FaceRegion, FaceRegionGeometry> = regions.toMap()

    init {
        require(renderTimestampNs >= 0L)
        require(cameraSensorTimestampNs == cameraFrame.sensorTimestampNs)
        require(canonicalLandmarks.topology == globalTopology)
        require(displayLandmarks.topology == globalTopology)
        require(canonicalLandmarks.coordinateSpace == FaceCoordinateSpace.FACE_LOCAL_METERS)
        require(displayLandmarks.coordinateSpace == FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT)
    }

    fun region(region: FaceRegion): FaceRegionGeometry? = regions[region]
}
