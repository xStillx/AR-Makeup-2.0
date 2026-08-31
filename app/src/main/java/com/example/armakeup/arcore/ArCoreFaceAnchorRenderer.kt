package com.example.armakeup.arcore

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.makeup.LipstickPigmentPalette
import com.example.armakeup.makeup.ReferenceLipstickPigments
import com.example.armakeup.makeup.ReferenceLipstickRenderProfiles
import com.example.armakeup.tracking.CanonicalFaceTransform
import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.NormalizedImageTransform
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Draws the ARCore front-camera image and one face-local point centered between the inner lips. */
internal class ArCoreFaceAnchorRenderer(
    private val displayRotation: () -> Int,
    private val imageRotationDegrees: () -> Int,
    private val onStatus: (String) -> Unit,
    private val onFatalError: (String) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    private var session: Session? = null

    @Volatile
    private var mediaPipeTracker: ArCoreMediaPipeLipTracker? = null

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var cameraTextureId = 0
    private var backgroundProgram = 0
    private var pointProgram = 0
    private var lipMeshProgram = 0
    private var cameraTextureBoundSession: Session? = null

    private val transformedCameraUvs = directFloatBuffer(8)
    private val lipAnchorVertex = directFloatBuffer(3)
    private val mappedMediaPipeOuterLipVertices =
        directFloatBuffer(LipLandmarkTopology.outerContour.size * 3)
    private val mappedMediaPipeInnerLipVertices =
        directFloatBuffer(LipLandmarkTopology.innerContour.size * 3)
    private val projectedArCoreLipVertices = directFloatBuffer(
        (LipLandmarkTopology.outerContour.size + LipLandmarkTopology.innerContour.size) * 3,
    )
    private val anchorTransport = TimestampedLipAnchorTransport()
    private val lipVisibilityGate = HeadDownLipVisibilityGate()
    private val lipTessellator = LipMeshTessellator()
    private val outerContourPoints = FloatArray(LipLandmarkTopology.outerContour.size * 2)
    private val innerContourPoints = FloatArray(LipLandmarkTopology.innerContour.size * 2)
    private val tessellatedLipVertices = directFloatBuffer(
        lipTessellator.vertexCount * LipMeshTessellator.VERTEX_COMPONENT_COUNT,
    )
    private val tessellatedLipIndices = directShortBuffer(lipTessellator.indices)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val clipPoint = FloatArray(4)
    private val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private val cameraUvCorners = FloatArray(8)

    @Volatile
    private var lipstickFinish = LipstickFinish.SATIN

    fun setLipstickFinish(value: LipstickFinish) {
        lipstickFinish = value
        Log.i(TAG, "Lipstick finish: $value")
    }

    private var statusWindowStartNs = 0L
    private var statusWindowFrames = 0

    fun bindSession(value: Session?) {
        session = value
        cameraTextureBoundSession = null
        anchorTransport.reset()
        lipVisibilityGate.reset()
    }

    fun bindMediaPipeTracker(value: ArCoreMediaPipeLipTracker?) {
        mediaPipeTracker = value
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        cameraTextureId = createExternalTexture()
        backgroundProgram = createProgram(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER)
        pointProgram = createProgram(POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER)
        lipMeshProgram = createProgram(LIP_MESH_VERTEX_SHADER, LIP_MESH_FRAGMENT_SHADER)
        checkGlError("surface creation")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val activeSession = session ?: return
        try {
            if (cameraTextureBoundSession !== activeSession) {
                activeSession.setCameraTextureName(cameraTextureId)
                cameraTextureBoundSession = activeSession
            }
            activeSession.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            val frame = activeSession.update()
            val timestampNs = frame.timestamp

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (timestampNs != 0L) {
                drawCameraBackground(frame)
                mediaPipeTracker?.tryDetect(frame, imageRotationDegrees())
            }

            val face = activeSession
                .getAllTrackables(AugmentedFace::class.java)
                .firstOrNull { it.trackingState == TrackingState.TRACKING }
            val screenPoint = face?.let { drawLipAnchor(frame.camera, it) }
            if (timestampNs != 0L && screenPoint != null) {
                anchorTransport.record(timestampNs, screenPoint.first, screenPoint.second)
            } else if (face == null) {
                anchorTransport.reset()
                lipVisibilityGate.reset()
            }
            val pitchRadians = face?.let {
                CanonicalFaceTransform.fromColumnMajor(viewModel)?.metricPitchRadians
            }
            val lipVisible = pitchRadians?.let(lipVisibilityGate::update) ?: false
            val hybridStatus = face?.let {
                drawMediaPipeLipContour(timestampNs, it, lipVisible, pitchRadians)
            }
            publishStatus(timestampNs, face != null, screenPoint, hybridStatus)
        } catch (error: CameraNotAvailableException) {
            Log.e(TAG, "ARCore camera became unavailable", error)
            onFatalError("ARCore camera unavailable: ${error.message ?: "unknown error"}")
            bindSession(null)
        } catch (error: RuntimeException) {
            Log.e(TAG, "ARCore render failure", error)
            onFatalError("ARCore render failed: ${error.message ?: error.javaClass.simpleName}")
            bindSession(null)
        }
    }

    private fun drawCameraBackground(frame: com.google.ar.core.Frame) {
        transformedCameraUvs.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            FULLSCREEN_QUAD,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedCameraUvs,
        )

        transformedCameraUvs.position(0)
        transformedCameraUvs.get(cameraUvCorners)
        transformedCameraUvs.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(backgroundProgram, "uCamera"), 0)

        FULLSCREEN_QUAD.position(0)
        val position = GLES20.glGetAttribLocation(backgroundProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, FULLSCREEN_QUAD)

        transformedCameraUvs.position(0)
        val texCoord = GLES20.glGetAttribLocation(backgroundProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, transformedCameraUvs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun drawLipAnchor(
        camera: com.google.ar.core.Camera,
        face: AugmentedFace,
    ): Pair<Float, Float>? {
        val vertices = face.meshVertices
        val requiredFloats = (maxOf(UPPER_INNER_LIP_INDEX, LOWER_INNER_LIP_INDEX) + 1) * 3
        if (vertices.limit() < requiredFloats) return null

        val x = (vertices[UPPER_INNER_LIP_INDEX * 3] + vertices[LOWER_INNER_LIP_INDEX * 3]) * 0.5f
        val y = (vertices[UPPER_INNER_LIP_INDEX * 3 + 1] +
            vertices[LOWER_INNER_LIP_INDEX * 3 + 1]) * 0.5f
        val z = (vertices[UPPER_INNER_LIP_INDEX * 3 + 2] +
            vertices[LOWER_INNER_LIP_INDEX * 3 + 2]) * 0.5f

        lipAnchorVertex.position(0)
        lipAnchorVertex.put(x).put(y).put(z).position(0)
        camera.getProjectionMatrix(projection, 0, NEAR_METERS, FAR_METERS)
        camera.getViewMatrix(view, 0)
        face.centerPose.toMatrix(model, 0)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        if (DRAW_DIAGNOSTIC_POINTS) drawPointCloud(
            vertices = lipAnchorVertex,
            pointCount = 1,
            transform = mvp,
            pointSize = 30f,
            color = MAGENTA,
            ringColor = WHITE,
        )

        Matrix.multiplyMV(clipPoint, 0, mvp, 0, floatArrayOf(x, y, z, 1f), 0)
        if (clipPoint[3] <= 0f) return null
        val ndcX = clipPoint[0] / clipPoint[3]
        val ndcY = clipPoint[1] / clipPoint[3]
        return Pair((ndcX + 1f) * 0.5f, (1f - ndcY) * 0.5f)
    }

    private fun drawMediaPipeLipContour(
        cameraTimestampNs: Long,
        face: AugmentedFace,
        lipVisible: Boolean,
        pitchRadians: Float?,
    ): HybridStatus? {
        val observation = mediaPipeTracker?.latest() ?: return null
        if (observation.inputWidth <= 0 || observation.inputHeight <= 0) return null
        val coordinates = observation.coordinates
        val maximumRequiredIndex = maxOf(
            LipLandmarkTopology.outerContour.maxOrNull() ?: 0,
            LipLandmarkTopology.innerContour.maxOrNull() ?: 0,
        )
        if (coordinates.size / 3 <= maximumRequiredIndex) return null

        val imageTransform = NormalizedImageTransform(
            rotationDegrees = observation.rotationDegrees,
            mirrorHorizontal = true,
        )
        val fillTransform = FillCenterTransform.calculate(
            viewWidth = viewportWidth,
            viewHeight = viewportHeight,
            sourceWidth = observation.inputWidth,
            sourceHeight = observation.inputHeight,
        )
        val anchorCorrection = anchorTransport.correctionFor(
            measurementTimestampNs = observation.sensorTimestampNs,
            renderTimestampNs = cameraTimestampNs,
        )
        val translationX = anchorCorrection?.translationX ?: 0f
        val translationY = anchorCorrection?.translationY ?: 0f

        fun writeLandmark(
            landmark: Int,
            pointIndex: Int,
            points: FloatArray,
            vertices: FloatBuffer,
        ) {
            val coordinateIndex = landmark * 3
            val rawX = coordinates[coordinateIndex]
            val rawY = coordinates[coordinateIndex + 1]
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayY = imageTransform.mapY(rawX, rawY)
            val screenX = fillTransform.mapX(displayX, observation.inputWidth) /
                viewportWidth + translationX
            val screenY = fillTransform.mapY(displayY, observation.inputHeight) /
                viewportHeight + translationY
            val outputIndex = pointIndex * 2
            points[outputIndex] = screenX
            points[outputIndex + 1] = screenY
            vertices
                .put(screenX * 2f - 1f)
                .put(1f - screenY * 2f)
                .put(0f)
        }

        mappedMediaPipeOuterLipVertices.clear()
        LipLandmarkTopology.outerContour.forEachIndexed { pointIndex, landmark ->
            writeLandmark(
                landmark,
                pointIndex,
                outerContourPoints,
                mappedMediaPipeOuterLipVertices,
            )
        }
        mappedMediaPipeOuterLipVertices.position(0)

        mappedMediaPipeInnerLipVertices.clear()
        LipLandmarkTopology.innerContour.forEachIndexed { pointIndex, landmark ->
            writeLandmark(
                landmark,
                pointIndex,
                innerContourPoints,
                mappedMediaPipeInnerLipVertices,
            )
        }
        mappedMediaPipeInnerLipVertices.position(0)

        val tessellated = lipTessellator.tessellate(
            outerContour = outerContourPoints,
            innerContour = innerContourPoints,
            upperProfile = ReferenceMatteLipstickProfile.upper,
            lowerProfile = ReferenceMatteLipstickProfile.lower,
        )
        tessellatedLipVertices.clear()
        var sourceIndex = 0
        repeat(lipTessellator.vertexCount) {
            tessellatedLipVertices
                .put(tessellated[sourceIndex + LipMeshTessellator.X_COMPONENT_OFFSET] * 2f - 1f)
                .put(1f - tessellated[sourceIndex + LipMeshTessellator.Y_COMPONENT_OFFSET] * 2f)
                .put(tessellated[sourceIndex + LipMeshTessellator.NORMAL_X_COMPONENT_OFFSET])
                .put(tessellated[sourceIndex + LipMeshTessellator.NORMAL_Y_COMPONENT_OFFSET])
                .put(tessellated[sourceIndex + LipMeshTessellator.NORMAL_Z_COMPONENT_OFFSET])
                .put(tessellated[sourceIndex + LipMeshTessellator.COVERAGE_COMPONENT_OFFSET])
                .put(tessellated[sourceIndex + LipMeshTessellator.RING_COMPONENT_OFFSET])
                .put(tessellated[sourceIndex + LipMeshTessellator.ARC_COMPONENT_OFFSET])
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
        }
        tessellatedLipVertices.position(0)
        if (lipVisible) drawLipMesh()

        if (DRAW_DIAGNOSTIC_POINTS && lipVisible) drawPointCloud(
            vertices = mappedMediaPipeOuterLipVertices,
            pointCount = LipLandmarkTopology.outerContour.size,
            transform = identity,
            pointSize = 11f,
            color = CYAN,
            ringColor = DARK_CYAN,
        )
        if (DRAW_DIAGNOSTIC_POINTS && lipVisible) drawPointCloud(
            vertices = mappedMediaPipeInnerLipVertices,
            pointCount = LipLandmarkTopology.innerContour.size,
            transform = identity,
            pointSize = 9f,
            color = YELLOW,
            ringColor = DARK_YELLOW,
        )

        val meshVertices = face.meshVertices
        projectedArCoreLipVertices.clear()
        var arCorePointCount = 0
        fun appendArCoreLandmark(landmark: Int) {
            if (meshVertices.limit() / 3 <= landmark) return
            val projected = projectFaceVertex(meshVertices, landmark) ?: return
            projectedArCoreLipVertices
                .put(projected.first * 2f - 1f)
                .put(1f - projected.second * 2f)
                .put(0f)
            arCorePointCount++
        }
        LipLandmarkTopology.outerContour.forEach(::appendArCoreLandmark)
        LipLandmarkTopology.innerContour.forEach(::appendArCoreLandmark)
        projectedArCoreLipVertices.position(0)
        if (DRAW_DIAGNOSTIC_POINTS && lipVisible) drawPointCloud(
            vertices = projectedArCoreLipVertices,
            pointCount = arCorePointCount,
            transform = identity,
            pointSize = 5f,
            color = GREEN,
            ringColor = DARK_GREEN,
        )

        val observationAgeNs = (cameraTimestampNs - observation.sensorTimestampNs)
            .coerceAtLeast(0L)
        val anchorTranslationPixels = hypot(
            translationX * viewportWidth,
            translationY * viewportHeight,
        )
        return HybridStatus(
            mediaPipeFps = observation.smoothedFps,
            inferenceDurationMs = observation.inferenceDurationMs,
            conversionDurationMs = observation.conversionDurationMs,
            cameraAgeMs = observationAgeNs / 1_000_000f,
            anchorTranslationPixels = anchorTranslationPixels,
            anchorCorrectionAvailable = anchorCorrection != null,
            pitchDegrees = pitchRadians?.let { Math.toDegrees(it.toDouble()).toFloat() },
            lipVisible = lipVisible,
        )
    }
    private fun projectFaceVertex(vertices: FloatBuffer, landmarkIndex: Int): Pair<Float, Float>? {
        val index = landmarkIndex * 3
        val x = vertices[index]
        val y = vertices[index + 1]
        val z = vertices[index + 2]
        val clipX = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val clipY = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        val clipW = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
        if (!clipW.isFinite() || clipW <= 0f) return null
        return Pair((clipX / clipW + 1f) * 0.5f, (1f - clipY / clipW) * 0.5f)
    }

    private fun drawLipMesh() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(lipMeshProgram)
        val profile = ReferenceLipstickRenderProfiles.forFinish(lipstickFinish)
        val pigment = when (profile.pigmentPalette) {
            LipstickPigmentPalette.PRODUCT_CLASSIC_RED_999 -> PRODUCT_PIGMENT
            LipstickPigmentPalette.TRACKING_MAGENTA -> TRACKING_PIGMENT
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(lipMeshProgram, "uCamera"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvBottomLeft"), cameraUvCorners[0], cameraUvCorners[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvBottomRight"), cameraUvCorners[2], cameraUvCorners[3])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvTopLeft"), cameraUvCorners[4], cameraUvCorners[5])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvTopRight"), cameraUvCorners[6], cameraUvCorners[7])
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(lipMeshProgram, "uPigment"), 1, pigment, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uCoverageMultiplier"), profile.coverageMultiplier)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uLuminancePreservation"), profile.luminancePreservation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uMinimumLuminanceGain"), profile.minimumLuminanceGain)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uMaximumLuminanceGain"), profile.maximumLuminanceGain)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uRoughness"), profile.optics.roughness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uSpecularStrength"), profile.optics.specularStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uHighlightRetention"), profile.optics.highlightRetention)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uMicroTextureRetention"), profile.optics.microTextureRetention)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uWetInnerEdgeStrength"), profile.optics.wetInnerEdgeStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uSurfaceDetailRetention"), profile.optics.surfaceDetailRetention)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uSatinGlowStrength"), profile.optics.satinGlowStrength)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uIlluminationSampleStep"),
            ILLUMINATION_SAMPLE_RADIUS_PIXELS / viewportWidth,
            ILLUMINATION_SAMPLE_RADIUS_PIXELS / viewportHeight,
        )

        val strideBytes = LipMeshTessellator.VERTEX_COMPONENT_COUNT * Float.SIZE_BYTES
        val position = GLES20.glGetAttribLocation(lipMeshProgram, "aPosition")
        tessellatedLipVertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            strideBytes,
            tessellatedLipVertices,
        )
        val normal = GLES20.glGetAttribLocation(lipMeshProgram, "aNormal")
        tessellatedLipVertices.position(2)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(
            normal,
            3,
            GLES20.GL_FLOAT,
            false,
            strideBytes,
            tessellatedLipVertices,
        )
        val coverage = GLES20.glGetAttribLocation(lipMeshProgram, "aCoverage")
        tessellatedLipVertices.position(5)
        GLES20.glEnableVertexAttribArray(coverage)
        GLES20.glVertexAttribPointer(
            coverage,
            1,
            GLES20.GL_FLOAT,
            false,
            strideBytes,
            tessellatedLipVertices,
        )
        tessellatedLipIndices.position(0)
        val lipUv = GLES20.glGetAttribLocation(lipMeshProgram, "aLipUv")
        tessellatedLipVertices.position(6)
        GLES20.glEnableVertexAttribArray(lipUv)
        GLES20.glVertexAttribPointer(
            lipUv,
            2,
            GLES20.GL_FLOAT,
            false,
            strideBytes,
            tessellatedLipVertices,
        )
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            lipTessellator.indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            tessellatedLipIndices,
        )
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(lipUv)
        GLES20.glDisableVertexAttribArray(coverage)
    }
    private fun drawPointCloud(
        vertices: FloatBuffer,
        pointCount: Int,
        transform: FloatArray,
        pointSize: Float,
        color: FloatArray,
        ringColor: FloatArray,
    ) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(pointProgram)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(pointProgram, "uMvp"),
            1,
            false,
            transform,
            0,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pointProgram, "uPointSize"), pointSize)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(pointProgram, "uColor"), 1, color, 0)
        GLES20.glUniform3fv(
            GLES20.glGetUniformLocation(pointProgram, "uRingColor"),
            1,
            ringColor,
            0,
        )
        val position = GLES20.glGetAttribLocation(pointProgram, "aPosition")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun publishStatus(
        timestampNs: Long,
        faceTracked: Boolean,
        screenPoint: Pair<Float, Float>?,
        hybridStatus: HybridStatus?,
    ) {
        if (timestampNs == 0L) return
        if (statusWindowStartNs == 0L) statusWindowStartNs = timestampNs
        statusWindowFrames++
        val durationNs = timestampNs - statusWindowStartNs
        if (durationNs < STATUS_INTERVAL_NS) return

        val fps = (statusWindowFrames - 1).coerceAtLeast(0) * 1_000_000_000.0 / durationNs
        val pointLabel = screenPoint?.let { point ->
            "point ${(point.first * viewportWidth).roundToInt()},${(point.second * viewportHeight).roundToInt()}"
        } ?: "point —"
        val state = if (faceTracked) "FACE TRACKING" else "SEARCHING FOR FACE"
        val hybridLabel = hybridStatus?.let { status ->
            val anchorLabel = if (status.anchorCorrectionAvailable) {
                String.format(Locale.US, "anchor %.1f px", status.anchorTranslationPixels)
            } else {
                "anchor unavailable"
            }
            val pitchLabel = status.pitchDegrees?.let {
                String.format(Locale.US, "%.1f°", it)
            } ?: "—"
            val visibilityLabel = if (status.lipVisible) "VISIBLE" else "HIDDEN: HEAD DOWN"
            String.format(
                Locale.US,
                "MediaPipe %.1f FPS · YUV %.0f + ML %.0f ms · age %.0f ms · %s · " +
                    "pitch %s · %s",
                status.mediaPipeFps,
                status.conversionDurationMs,
                status.inferenceDurationMs,
                status.cameraAgeMs,
                anchorLabel,
                pitchLabel,
                visibilityLabel,
            )
        } ?: "MediaPipe: waiting for synchronized result"
        val text = String.format(
            Locale.US,
            "%s\nARCore %.1f FPS · %s\n%s\nanchored tessellated lipstick",
            state,
            fps,
            pointLabel,
            hybridLabel,
        )
        onStatus(text)
        Log.d(TAG, text.replace('\n', ' '))
        statusWindowStartNs = timestampNs
        statusWindowFrames = 0
    }

    private data class HybridStatus(
        val mediaPipeFps: Float,
        val inferenceDurationMs: Float,
        val conversionDurationMs: Float,
        val cameraAgeMs: Float,
        val anchorTranslationPixels: Float,
        val anchorCorrectionAvailable: Boolean,
        val pitchDegrees: Float?,
        val lipVisible: Boolean,
    )

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) {
            "OpenGL program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            "OpenGL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun checkGlError(label: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$label: OpenGL error 0x${error.toString(16)}" }
    }

    private companion object {
        const val TAG = "ARMakeupArCore"
        const val UPPER_INNER_LIP_INDEX = 13
        const val LOWER_INNER_LIP_INDEX = 14
        const val NEAR_METERS = 0.05f
        const val FAR_METERS = 100f
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        const val DRAW_DIAGNOSTIC_POINTS = false
        const val ILLUMINATION_SAMPLE_RADIUS_PIXELS = 14f

        val MAGENTA = floatArrayOf(1f, 0.05f, 0.8f)
        val CYAN = floatArrayOf(0.05f, 1f, 0.95f)
        val DARK_CYAN = floatArrayOf(0f, 0.25f, 0.25f)
        val YELLOW = floatArrayOf(1f, 0.9f, 0.05f)
        val DARK_YELLOW = floatArrayOf(0.3f, 0.2f, 0f)
        val GREEN = floatArrayOf(0.1f, 1f, 0.15f)
        val DARK_GREEN = floatArrayOf(0f, 0.25f, 0.02f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
        val PRODUCT_PIGMENT = floatArrayOf(
            ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_RED_SRGB,
            ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_GREEN_SRGB,
            ReferenceLipstickPigments.PRODUCT_CLASSIC_RED_999_BLUE_SRGB,
        )
        val TRACKING_PIGMENT = floatArrayOf(1f, 0f, 0.831f)

        val FULLSCREEN_QUAD: FloatBuffer = directFloatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            ),
        )

        const val BACKGROUND_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        const val BACKGROUND_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uCamera, vTexCoord);
            }
        """

        const val LIP_MESH_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec3 aNormal;
            attribute float aCoverage;
            attribute vec2 aLipUv;
            varying vec2 vDisplayUv;
            varying vec3 vNormal;
            varying float vCoverage;
            varying vec2 vLipUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vDisplayUv = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
                vNormal = aNormal;
                vCoverage = aCoverage;
                vLipUv = aLipUv;
            }
        """

        const val LIP_MESH_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            uniform vec2 uUvBottomLeft;
            uniform vec2 uUvBottomRight;
            uniform vec2 uUvTopLeft;
            uniform vec2 uUvTopRight;
            uniform vec3 uPigment;
            uniform float uCoverageMultiplier;
            uniform float uLuminancePreservation;
            uniform float uMinimumLuminanceGain;
            uniform float uMaximumLuminanceGain;
            uniform float uRoughness;
            uniform float uSpecularStrength;
            uniform float uHighlightRetention;
            uniform float uMicroTextureRetention;
            uniform float uWetInnerEdgeStrength;
            uniform float uSurfaceDetailRetention;
            uniform float uSatinGlowStrength;
            uniform vec2 uIlluminationSampleStep;
            varying vec2 vDisplayUv;
            varying vec3 vNormal;
            varying float vCoverage;
            varying vec2 vLipUv;

            vec2 cameraUv(vec2 displayUv) {
                vec2 top = mix(uUvTopLeft, uUvTopRight, displayUv.x);
                vec2 bottom = mix(uUvBottomLeft, uUvBottomRight, displayUv.x);
                return mix(top, bottom, displayUv.y);
            }

            vec3 srgbToLinear(vec3 value) {
                return pow(max(value, vec3(0.0)), vec3(2.2));
            }

            vec3 linearToSrgb(vec3 value) {
                return pow(max(value, vec3(0.0)), vec3(0.45454545));
            }

            vec3 cameraLinearAt(vec2 displayUv) {
                vec2 uv = cameraUv(clamp(displayUv, vec2(0.0), vec2(1.0)));
                return srgbToLinear(texture2D(uCamera, uv).rgb);
            }

            void main() {
                const vec3 luminanceWeights = vec3(0.2126, 0.7152, 0.0722);
                vec3 cameraLinear = cameraLinearAt(vDisplayUv);
                float coverage = clamp(vCoverage * uCoverageMultiplier, 0.0, 1.0);
                float cameraLuminance = max(dot(cameraLinear, luminanceWeights), 0.0001);

                vec3 cameraLeft = cameraLinearAt(
                    vDisplayUv - vec2(uIlluminationSampleStep.x, 0.0)
                );
                vec3 cameraRight = cameraLinearAt(
                    vDisplayUv + vec2(uIlluminationSampleStep.x, 0.0)
                );
                vec3 cameraBottom = cameraLinearAt(
                    vDisplayUv - vec2(0.0, uIlluminationSampleStep.y)
                );
                vec3 cameraTop = cameraLinearAt(
                    vDisplayUv + vec2(0.0, uIlluminationSampleStep.y)
                );
                float luminanceLeft = dot(cameraLeft, luminanceWeights);
                float luminanceRight = dot(cameraRight, luminanceWeights);
                float luminanceBottom = dot(cameraBottom, luminanceWeights);
                float luminanceTop = dot(cameraTop, luminanceWeights);
                vec3 neighborhoodLinear = 0.25 * (
                    cameraLeft + cameraRight + cameraBottom + cameraTop
                );
                float neighborhoodLuminance = max(
                    dot(neighborhoodLinear, luminanceWeights),
                    0.0001
                );
                vec2 illuminationGradient = clamp(
                    vec2(luminanceRight - luminanceLeft, luminanceTop - luminanceBottom) * 4.5,
                    vec2(-0.7),
                    vec2(0.7)
                );
                vec3 lightDirection = normalize(vec3(illuminationGradient, 0.86));
                vec3 halfDirection = normalize(lightDirection + vec3(0.0, 0.0, 1.0));

                float positiveCameraDetail = max(
                    cameraLuminance - neighborhoodLuminance,
                    0.0
                );
                float surfaceLuminance = mix(
                    neighborhoodLuminance,
                    cameraLuminance,
                    uSurfaceDetailRetention
                );
                float suppressedHighlight = positiveCameraDetail *
                    (1.0 - uHighlightRetention) * coverage;
                float materialLuminance = max(surfaceLuminance - suppressedHighlight, 0.0001);
                vec3 pigmentLinear = srgbToLinear(uPigment);
                float pigmentLuminance = max(dot(pigmentLinear, luminanceWeights), 0.0001);
                float luminanceGain = clamp(
                    materialLuminance / pigmentLuminance,
                    uMinimumLuminanceGain,
                    uMaximumLuminanceGain
                );
                vec3 luminancePreservingPigment =
                    pigmentLinear * luminanceGain;
                vec3 renderedPigment = mix(
                    pigmentLinear,
                    luminancePreservingPigment,
                    uLuminancePreservation
                );
                vec3 pigmented = mix(cameraLinear, renderedPigment, coverage);

                vec3 lipNormal = normalize(vNormal);
                float roughness = clamp(uRoughness, 0.08, 1.0);
                float specularPower = mix(112.0, 9.0, roughness * roughness);
                float normalLobe = pow(
                    max(dot(lipNormal, halfDirection), 0.0),
                    specularPower
                );
                float nativeHighlight = smoothstep(0.006, 0.075, positiveCameraDetail);
                float illuminationConfidence = smoothstep(
                    0.008,
                    0.11,
                    length(illuminationGradient)
                );
                float cameraAnchoredLobe = normalLobe * mix(
                    0.18,
                    1.0,
                    max(nativeHighlight, illuminationConfidence)
                );
                float arcCenter = sin(3.14159265 * clamp(vLipUv.y, 0.0, 1.0));
                float innerEdge = smoothstep(0.56, 0.88, vLipUv.x) *
                    (1.0 - smoothstep(0.93, 1.0, vLipUv.x)) * arcCenter;
                float legacyWetEdgeGain = 1.0 + uWetInnerEdgeStrength * innerEdge * 2.2;
                float retainedNativeSpecular = positiveCameraDetail *
                    uHighlightRetention * 0.55;
                float legacySpecular = uSpecularStrength * coverage *
                    legacyWetEdgeGain * (0.045 * cameraAnchoredLobe + retainedNativeSpecular);

                float adaptiveGloss = smoothstep(0.12, 0.32, uWetInnerEdgeStrength);
                float sceneLightLevel = smoothstep(0.015, 0.45, neighborhoodLuminance);
                float lightingEvidence = max(
                    nativeHighlight,
                    max(illuminationConfidence, sceneLightLevel * 0.42)
                );
                float directionalContrast = smoothstep(
                    0.04,
                    0.55,
                    length(illuminationGradient)
                );
                float highlightArcCenter = clamp(
                    0.5 + illuminationGradient.x * 0.38,
                    0.16,
                    0.84
                );
                float highlightArcHalfWidth = mix(0.32, 0.14, directionalContrast);
                float highlightArcDistance = abs(vLipUv.y - highlightArcCenter);
                float localizedArcHighlight = 1.0 - smoothstep(
                    highlightArcHalfWidth * 0.62,
                    highlightArcHalfWidth,
                    highlightArcDistance
                );
                float adaptiveFilmGain = 1.0 +
                    uWetInnerEdgeStrength * sceneLightLevel * 0.35;
                float adaptiveSpecular = uSpecularStrength * coverage *
                    adaptiveFilmGain * 0.065 * normalLobe * lightingEvidence *
                    localizedArcHighlight;
                vec3 illuminationTint = clamp(
                    neighborhoodLinear / neighborhoodLuminance,
                    vec3(0.72),
                    vec3(1.28)
                );
                float satinGlow = clamp(uSatinGlowStrength, 0.0, 1.0);
                float satinBroadLobe = pow(
                    max(dot(lipNormal, halfDirection), 0.0),
                    4.0
                );
                float satinLightResponse = mix(
                    0.42,
                    1.0,
                    max(sceneLightLevel, illuminationConfidence)
                );
                float satinSpecular = uSpecularStrength * coverage * 0.060 *
                    satinBroadLobe * satinLightResponse;
                vec3 satinNativeHighlightColor = min(
                    max(cameraLinear - neighborhoodLinear, vec3(0.0)),
                    vec3(0.12)
                ) * uHighlightRetention * uSpecularStrength * coverage * 0.34;
                vec3 nativeSpecularColor = min(
                    max(cameraLinear - neighborhoodLinear, vec3(0.0)),
                    vec3(0.22)
                ) * uHighlightRetention * uSpecularStrength * coverage * 0.72;
                vec3 legacySpecularColor = vec3(1.0, 0.94, 0.92) * legacySpecular;
                vec3 adaptiveSpecularColor =
                    illuminationTint * adaptiveSpecular + nativeSpecularColor;
                vec3 satinSpecularColor =
                    illuminationTint * satinSpecular + satinNativeHighlightColor;
                vec3 baseSpecularColor = mix(
                    legacySpecularColor,
                    satinSpecularColor,
                    satinGlow
                );
                vec3 specularColor = mix(
                    baseSpecularColor,
                    adaptiveSpecularColor,
                    adaptiveGloss
                );

                float textureDetail = cameraLuminance - neighborhoodLuminance;
                float textureCorrection = textureDetail * uMicroTextureRetention *
                    coverage * 0.055;
                float satinDiffuseGlow = satinGlow * coverage * sceneLightLevel *
                    (0.022 + 0.032 * satinBroadLobe);
                pigmented *= 1.0 + satinDiffuseGlow;
                vec3 chromaDirection = pigmented / max(
                    dot(pigmented, luminanceWeights),
                    0.0001
                );
                pigmented += chromaDirection * textureCorrection;
                pigmented += specularColor;
                gl_FragColor = vec4(linearToSrgb(max(pigmented, vec3(0.0))), 1.0);
            }
        """
        const val POINT_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform float uPointSize;
            attribute vec3 aPosition;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                gl_PointSize = uPointSize;
            }
        """

        const val POINT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            uniform vec3 uRingColor;
            void main() {
                vec2 delta = gl_PointCoord - vec2(0.5);
                float radius = length(delta);
                if (radius > 0.5) discard;
                float ring = step(0.30, radius);
                vec3 color = mix(uColor, uRingColor, ring);
                gl_FragColor = vec4(color, 1.0);
            }
        """

        fun directShortBuffer(values: ShortArray): ShortBuffer = ByteBuffer
            .allocateDirect(values.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(values)
            .apply { position(0) }

        fun directFloatBuffer(size: Int): FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        fun directFloatBuffer(values: FloatArray): FloatBuffer = directFloatBuffer(values.size)
            .put(values)
            .apply { position(0) }
    }
}
