package com.example.armakeup.arcore

import android.opengl.Matrix
import android.view.Surface
import com.example.armakeup.tracking.face.FaceCameraFrameMetadata
import com.example.armakeup.tracking.face.FaceCoordinateSpace
import com.example.armakeup.tracking.face.FaceLandmarkSet
import com.example.armakeup.tracking.face.FaceFeatureObservationState
import com.example.armakeup.tracking.face.FaceMatrix4
import com.example.armakeup.tracking.face.FaceObservation
import com.example.armakeup.tracking.face.FaceObservationFeatures
import com.example.armakeup.tracking.face.FaceObservationQuality
import com.example.armakeup.tracking.face.FaceObservationRole
import com.example.armakeup.tracking.face.FacePose
import com.example.armakeup.tracking.face.FaceTopologies
import com.example.armakeup.tracking.face.FaceTrackingBackend
import com.example.armakeup.tracking.face.FaceSurfaceTopology
import com.example.armakeup.tracking.face.FaceSurfaceTextureCoordinates
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Camera
import java.util.concurrent.atomic.AtomicReference

/** Converts ARCore runtime objects into the model-independent FF3 observation contract. */
internal class ArCoreFaceObservationAdapter : FaceTrackingBackend {
    override val backendId: String = BACKEND_ID
    override val role: FaceObservationRole = FaceObservationRole.GLOBAL_POSE
    override val topology = FaceTopologies.ARCORE_AUGMENTED_FACE_468

    private val latestObservation = AtomicReference<FaceObservation?>()
    private val projection = FloatArray(FaceMatrix4.ELEMENT_COUNT)
    private val view = FloatArray(FaceMatrix4.ELEMENT_COUNT)
    private val model = FloatArray(FaceMatrix4.ELEMENT_COUNT)
    private val viewModel = FloatArray(FaceMatrix4.ELEMENT_COUNT)
    private val modelViewProjection = FloatArray(FaceMatrix4.ELEMENT_COUNT)
    private var cachedSurfaceTopology: FaceSurfaceTopology? = null
    private var cachedSurfaceTextureCoordinates: FaceSurfaceTextureCoordinates? = null

    fun create(
        sensorTimestampNs: Long,
        displayRotation: Int,
        analysisImageRotationDegrees: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        camera: Camera,
        face: AugmentedFace,
    ): FaceObservation? {
        if (sensorTimestampNs <= 0L) return null
        val vertices = face.meshVertices
        if (vertices.limit() < topology.pointCount * FaceLandmarkSet.COMPONENT_COUNT) return null
        val surfaceTopology = cachedSurfaceTopology ?: face.meshTriangleIndices.let { indices ->
            if (indices.limit() <= 0 ||
                indices.limit() % FaceSurfaceTopology.INDICES_PER_TRIANGLE != 0
            ) {
                return null
            }
            ShortArray(indices.limit()) { index -> indices[index] }
                .let { FaceSurfaceTopology.takeOwnership(topology, it) }
                .also { cachedSurfaceTopology = it }
        }
        val surfaceTextureCoordinates = cachedSurfaceTextureCoordinates
            ?: face.meshTextureCoordinates.let { textureCoordinates ->
                val requiredValues = topology.pointCount *
                    FaceSurfaceTextureCoordinates.COMPONENT_COUNT
                if (textureCoordinates.limit() < requiredValues) return null
                FloatArray(requiredValues) { index -> textureCoordinates[index] }
                    .let { FaceSurfaceTextureCoordinates.takeOwnership(topology, it) }
                    .also { cachedSurfaceTextureCoordinates = it }
            }

        camera.getProjectionMatrix(projection, 0, NEAR_METERS, FAR_METERS)
        camera.getViewMatrix(view, 0)
        face.centerPose.toMatrix(model, 0)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(modelViewProjection, 0, projection, 0, viewModel, 0)

        val canonicalCoordinates = FloatArray(topology.pointCount * FaceLandmarkSet.COMPONENT_COUNT)
        val displayCoordinates = FloatArray(canonicalCoordinates.size)
        repeat(topology.pointCount) { landmarkIndex ->
            val coordinate = landmarkIndex * FaceLandmarkSet.COMPONENT_COUNT
            val x = vertices[coordinate]
            val y = vertices[coordinate + 1]
            val z = vertices[coordinate + 2]
            canonicalCoordinates[coordinate] = x
            canonicalCoordinates[coordinate + 1] = y
            canonicalCoordinates[coordinate + 2] = z

            val clipX = modelViewProjection[0] * x + modelViewProjection[4] * y +
                modelViewProjection[8] * z + modelViewProjection[12]
            val clipY = modelViewProjection[1] * x + modelViewProjection[5] * y +
                modelViewProjection[9] * z + modelViewProjection[13]
            val clipZ = modelViewProjection[2] * x + modelViewProjection[6] * y +
                modelViewProjection[10] * z + modelViewProjection[14]
            val clipW = modelViewProjection[3] * x + modelViewProjection[7] * y +
                modelViewProjection[11] * z + modelViewProjection[15]
            if (!clipW.isFinite() || clipW <= 0f) return null
            displayCoordinates[coordinate] = (clipX / clipW + 1f) * 0.5f
            displayCoordinates[coordinate + 1] = (1f - clipY / clipW) * 0.5f
            displayCoordinates[coordinate + 2] = clipZ / clipW
        }

        return FaceObservation(
            backendId = backendId,
            role = role,
            sensorTimestampNs = sensorTimestampNs,
            topology = topology,
            canonicalLandmarks = FaceLandmarkSet.takeOwnership(
                topology,
                FaceCoordinateSpace.FACE_LOCAL_METERS,
                canonicalCoordinates,
            ),
            displayLandmarks = FaceLandmarkSet.takeOwnership(
                topology,
                FaceCoordinateSpace.NORMALIZED_DISPLAY_TOP_LEFT,
                displayCoordinates,
            ),
            surfaceTopology = surfaceTopology,
            surfaceTextureCoordinates = surfaceTextureCoordinates,
            pose = FacePose(FaceMatrix4.columnMajor(model)),
            cameraFrame = FaceCameraFrameMetadata(
                sensorTimestampNs = sensorTimestampNs,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                displayRotationDegrees = displayRotation.toDegrees(),
                analysisImageRotationDegrees = analysisImageRotationDegrees,
                frontCameraMirrored = true,
                clipFromCamera = FaceMatrix4.columnMajor(projection),
                cameraFromWorld = FaceMatrix4.columnMajor(view),
            ),
            quality = FaceObservationQuality(
                tracking = true,
                trackingConfidence = null,
                visibleFraction = null,
            ),
            features = FaceObservationFeatures(
                mouth = FaceFeatureObservationState(tracking = true),
                leftEye = FaceFeatureObservationState(tracking = true),
                rightEye = FaceFeatureObservationState(tracking = true),
            ),
        ).also(latestObservation::set)
    }

    override fun latestObservation(): FaceObservation? = latestObservation.get()

    fun clear() {
        latestObservation.set(null)
    }

    override fun close() = clear()

    private fun Int.toDegrees(): Int = when (this) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private companion object {
        const val BACKEND_ID = "arcore-augmented-faces"
        const val NEAR_METERS = 0.05f
        const val FAR_METERS = 100f
    }
}
