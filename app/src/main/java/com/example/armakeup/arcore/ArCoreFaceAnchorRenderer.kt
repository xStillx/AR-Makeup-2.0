package com.example.armakeup.arcore

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import com.example.armakeup.makeup.LipColorRenderingMode
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.tracking.CanonicalFaceTransform
import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.NormalizedImageTransform
import com.example.armakeup.tracking.LipContourTemporalRefiner
import com.example.armakeup.tracking.LipSemanticState
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
    private var pairedBackgroundProgram = 0
    private var pairedLipMeshProgram = 0
    private var lipBlurProgram = 0
    private var lipCompositeProgram = 0
    private var pairedLipCompositeProgram = 0
    private val pairedCameraFrames = PairedCameraFrameStore()
    private val lipBlurFrames = LipBlurFrameStore()
    private var pendingPair: CameraPair? = null
    private var presentedPair: CameraPair? = null
    private var presentedObservation: ArCoreMediaPipeLipTracker.Observation? = null
    private var configuredDisplayRotation = -1
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var cameraTextureBoundSession: Session? = null

    private val transformedCameraUvs = directFloatBuffer(8)
    private val framebufferUvs = directFloatBuffer(BUFFERED_CAMERA_UVS)
    private val lipBlurStep = FloatArray(2)
    private val lipBlurScissor = IntArray(4)
    private val lipAnchorVertex = directFloatBuffer(3)
    private val stableLipAnchorLocal = FloatArray(3)
    private var hasStableLipAnchor = false
    private val mappedMediaPipeOuterLipVertices =
        directFloatBuffer(LipLandmarkTopology.outerContour.size * 3)
    private val mappedMediaPipeInnerLipVertices =
        directFloatBuffer(LipLandmarkTopology.innerContour.size * 3)
    private val projectedArCoreLipVertices = directFloatBuffer(
        (LipLandmarkTopology.outerContour.size + LipLandmarkTopology.innerContour.size) * 3,
    )
    private val anchorTransport = TimestampedLipAnchorTransport()
    private val lipVisibilityGate = HeadDownLipVisibilityGate()
    private val lipContourRefiner = LipContourTemporalRefiner()
    private val lipTessellator = LipMeshTessellator(featherInnerBoundary = false)
    private val satinUvs = directFloatBuffer(IosLipMaterial.textureCoordinates(lipTessellator))
    private val satinSourceTexels = FloatArray(4)
    private val lipFrameDiagnostics = LipFrameDiagnostics()
    private val mediaPipeOnlyOuterContourPoints =
        FloatArray(LipLandmarkTopology.outerContour.size * 2)
    private val mediaPipeOnlyInnerContourPoints =
        FloatArray(LipLandmarkTopology.innerContour.size * 2)
    private val measuredOuterContourPoints = FloatArray(LipLandmarkTopology.outerContour.size * 2)
    private val measuredInnerContourPoints = FloatArray(LipLandmarkTopology.innerContour.size * 2)
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

    @Volatile
    private var lipstickTuning = LipstickTuning()

    @Volatile
    private var colorRenderingMode = LipColorRenderingMode.IOS_REFERENCE

    fun setLipstickTuning(value: LipstickTuning) {
        lipstickTuning = value
    }

    fun setLipstickFinish(value: LipstickFinish) {
        lipstickFinish = value
        Log.i(TAG, "Lipstick finish: $value")
    }

    fun setColorRenderingMode(value: LipColorRenderingMode) {
        colorRenderingMode = value
        Log.i(TAG, "Lip color rendering mode: $value")
    }

    private var statusWindowStartNs = 0L
    private var statusWindowFrames = 0
    private var statusWindowSyncInferredFrames = 0
    private var statusWindowSyncReusedFrames = 0
    private var statusWindowSyncMissingFrames = 0
    private var lastLipMetricsTimestampNs = 0L
    private var missingFaceFrameCount = 0

    @Volatile
    private var sameFrameSynchronizationEnabled = true

    fun setSameFrameSynchronizationEnabled(enabled: Boolean) {
        if (sameFrameSynchronizationEnabled == enabled) return
        sameFrameSynchronizationEnabled = enabled
        invalidateSameFrameState()
        if (!enabled) pairedCameraFrames.release()
        lipContourRefiner.reset()
        lipFrameDiagnostics.reset()
        lastLipMetricsTimestampNs = 0L
        resetSyncStatusWindow()
        Log.i(TAG, "Same-frame camera/ARCore/MediaPipe synchronization: $enabled")
    }

    fun bindSession(value: Session?) {
        session = value
        invalidateSameFrameState()
        cameraTextureBoundSession = null
        configuredDisplayRotation = -1
        anchorTransport.reset()
        lipVisibilityGate.reset()
        lipContourRefiner.reset()
        lipFrameDiagnostics.reset()
        lastLipMetricsTimestampNs = 0L
        hasStableLipAnchor = false
        missingFaceFrameCount = 0
    }

    fun bindMediaPipeTracker(value: ArCoreMediaPipeLipTracker?) {
        mediaPipeTracker = value
        invalidateSameFrameState()
    }

    /** Drop pending callbacks and displayed frames at lifecycle/timeline boundaries. No GL calls. */
    fun invalidateSameFrameState() {
        pendingPair = null
        presentedPair = null
        presentedObservation = null
        lipContourRefiner.reset()
        lipFrameDiagnostics.reset()
        lastLipMetricsTimestampNs = 0L
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        pairedCameraFrames.onContextCreated()
        lipBlurFrames.onContextCreated()
        invalidateSameFrameState()
        cameraTextureBoundSession = null
        configuredDisplayRotation = -1
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        cameraTextureId = createExternalTexture()
        backgroundProgram = createProgram(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER)
        pointProgram = createProgram(POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER)
        lipMeshProgram = createProgram(LIP_MESH_VERTEX_SHADER, LIP_MESH_FRAGMENT_SHADER)
        // Only the sampler changes. All pigment, lighting and coverage equations stay identical.
        pairedBackgroundProgram = createProgram(
            BACKGROUND_VERTEX_SHADER, bufferedCameraShader(BACKGROUND_FRAGMENT_SHADER),
        )
        pairedLipMeshProgram = createProgram(
            LIP_MESH_VERTEX_SHADER, bufferedCameraShader(LIP_MESH_FRAGMENT_SHADER),
        )
        lipBlurProgram = createProgram(BACKGROUND_VERTEX_SHADER, LIP_BLUR_FRAGMENT_SHADER)
        lipCompositeProgram = createProgram(
            LIP_COMPOSITE_VERTEX_SHADER, LIP_COMPOSITE_FRAGMENT_SHADER,
        )
        pairedLipCompositeProgram = createProgram(
            LIP_COMPOSITE_VERTEX_SHADER, bufferedCameraShader(LIP_COMPOSITE_FRAGMENT_SHADER),
        )
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
            val rotation = displayRotation()
            if (rotation != configuredDisplayRotation || viewportWidth != configuredWidth ||
                viewportHeight != configuredHeight
            ) {
                activeSession.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
                configuredDisplayRotation = rotation
                configuredWidth = viewportWidth
                configuredHeight = viewportHeight
                invalidateSameFrameState()
                anchorTransport.reset()
                lipContourRefiner.reset()
            }
            val frame = activeSession.update()
            val timestampNs = frame.timestamp

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (timestampNs == 0L) return
            if (!sameFrameSynchronizationEnabled) {
                drawCameraBackground(frame)
                mediaPipeTracker?.tryDetect(frame, imageRotationDegrees())
            }

            val face = activeSession
                .getAllTrackables(AugmentedFace::class.java)
                .firstOrNull { it.trackingState == TrackingState.TRACKING }
            val screenPoint = face?.let { drawLipAnchor(frame.camera, it) }
            if (timestampNs != 0L && screenPoint != null) {
                missingFaceFrameCount = 0
                anchorTransport.record(timestampNs, screenPoint.first, screenPoint.second)
            } else if (face == null) {
                anchorTransport.reset()
                missingFaceFrameCount++
                if (missingFaceFrameCount == FACE_LOSS_RESET_FRAME_COUNT) {
                    lipVisibilityGate.reset()
                    // Sync resets local geometry when its own buffered no-face frame is presented.
                    if (!sameFrameSynchronizationEnabled) {
                        lipContourRefiner.reset()
                        lipFrameDiagnostics.reset()
                        lastLipMetricsTimestampNs = 0L
                    }
                }
            }
            val pitchRadians = face?.let {
                CanonicalFaceTransform.fromColumnMajor(viewModel)?.metricPitchRadians
            }
            val lipVisible = pitchRadians?.let(lipVisibilityGate::update) ?: false
            if (sameFrameSynchronizationEnabled) {
                drawSynchronizedPair(frame, face != null, screenPoint, lipVisible, pitchRadians)
                return
            }
            val observation = mediaPipeTracker?.latest()
            val hybridStatus = face?.let { trackedFace ->
                observation?.let {
                    drawMediaPipeLipContour(
                        cameraTimestampNs = timestampNs,
                        face = trackedFace,
                        lipVisible = lipVisible,
                        pitchRadians = pitchRadians,
                        observation = it,
                        bufferedCameraAgeMs = 0f,
                    )
                }
            }
            publishStatus(
                timestampNs,
                face != null,
                screenPoint,
                hybridStatus,
                SyncFrameOutcome.NOT_ENABLED,
            )
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

    private fun drawSynchronizedPair(
        frame: com.google.ar.core.Frame,
        faceTracked: Boolean,
        screenPoint: Pair<Float, Float>?,
        lipVisible: Boolean,
        pitchRadians: Float?,
    ) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val tracker = mediaPipeTracker
        var promoted = false
        pendingPair?.let { pending ->
            val completion = tracker?.completionFor(pending.timestampNs)
            if (completion != null && nowNs - pending.capturedAtNs <= MAX_PAIR_AGE_NS) {
                check(completion.observation == null ||
                    completion.observation.sensorTimestampNs == pending.timestampNs)
                pairedCameraFrames.promote()
                presentedPair = pending
                presentedObservation = completion.observation
                pendingPair = null
                promoted = true
                if (completion.observation == null || !pending.faceTracked) lipContourRefiner.reset()
            } else if (nowNs - pending.capturedAtNs > MAX_PAIR_AGE_NS) {
                // Late results can never attach to a newer camera image.
                pendingPair = null
            }
        }
        if (presentedPair?.let { nowNs - it.capturedAtNs > MAX_PAIR_AGE_NS } == true) {
            presentedPair = null
            presentedObservation = null
            lipContourRefiner.reset()
        }

        if (pendingPair == null) {
            val submittedTimestampNs = tracker?.tryDetect(
                frame, imageRotationDegrees(), requireSameTimestamp = true,
            )
            if (submittedTimestampNs != null) {
                check(submittedTimestampNs == frame.timestamp)
                pairedCameraFrames.ensureSize(viewportWidth, viewportHeight)
                pairedCameraFrames.bindPending()
                try {
                    // Bake this frame's rotation, mirror and crop into a viewport-sized GPU image.
                    // The external camera texture may change on the next Session.update().
                    drawCameraBackground(frame)
                } finally {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                }
                pendingPair = CameraPair(
                    submittedTimestampNs, nowNs, faceTracked, screenPoint, lipVisible, pitchRadians,
                    satinSourceTexels.copyOf(),
                )
            }
        }

        val pair = presentedPair
        if (pair == null) {
            // Startup, tracking backend failure or expiry: live camera, never mismatched makeup.
            drawCameraBackground(frame)
            publishStatus(frame.timestamp, faceTracked, screenPoint, null, SyncFrameOutcome.MISSING)
            return
        }
        val textureId = pairedCameraFrames.presentedTextureId
        pair.satinSourceTexels.copyInto(satinSourceTexels)
        BUFFERED_CAMERA_UVS.copyInto(cameraUvCorners)
        transformedCameraUvs.clear()
        transformedCameraUvs.put(BUFFERED_CAMERA_UVS).position(0)
        drawCameraQuad(pairedBackgroundProgram, GLES20.GL_TEXTURE_2D, textureId)
        val hybridStatus = presentedObservation?.takeIf { pair.faceTracked }?.let {
            drawMediaPipeLipContour(
                cameraTimestampNs = pair.timestampNs,
                face = null, // Never read a live ARCore mesh/pose for a buffered image.
                lipVisible = pair.lipVisible,
                pitchRadians = pair.pitchRadians,
                observation = it,
                bufferedCameraAgeMs = (frame.timestamp - pair.timestampNs).coerceAtLeast(0L) / 1_000_000f,
                bufferedCameraTextureId = textureId,
            )
        }
        publishStatus(
            frame.timestamp, pair.faceTracked, pair.screenPoint, hybridStatus,
            if (promoted) SyncFrameOutcome.INFERRED else SyncFrameOutcome.REUSED,
        )
    }

    private data class CameraPair(
        val timestampNs: Long,
        val capturedAtNs: Long,
        val faceTracked: Boolean,
        val screenPoint: Pair<Float, Float>?,
        val lipVisible: Boolean,
        val pitchRadians: Float?,
        val satinSourceTexels: FloatArray,
    )

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
        // Invert texture-to-display mapping so 5/12/8 source-pixel radii follow rotation and crop.
        val dimensions = frame.camera.textureIntrinsics.imageDimensions
        val ax = cameraUvCorners[6] - cameraUvCorners[4]
        val ay = cameraUvCorners[7] - cameraUvCorners[5]
        val bx = cameraUvCorners[0] - cameraUvCorners[4]
        val by = cameraUvCorners[1] - cameraUvCorners[5]
        val determinant = ax * by - bx * ay
        if (kotlin.math.abs(determinant) > 1e-6f && dimensions[0] > 0 && dimensions[1] > 0) {
            // iOS material samples a BGRA image with a 512-pixel long edge.
            // Equivalent source distances, without a new camera stream or CPU resize.
            val sourceScale = maxOf(dimensions[0], dimensions[1]) / 512f
            satinSourceTexels[0] = by / determinant / dimensions[0] * sourceScale
            satinSourceTexels[1] = -ay / determinant / dimensions[0] * sourceScale
            satinSourceTexels[2] = -bx / determinant / dimensions[1] * sourceScale
            satinSourceTexels[3] = ax / determinant / dimensions[1] * sourceScale
        } else {
            satinSourceTexels[0] = 1f / viewportWidth
            satinSourceTexels[1] = 0f
            satinSourceTexels[2] = 0f
            satinSourceTexels[3] = 1f / viewportHeight
        }
        transformedCameraUvs.position(0)
        drawCameraQuad(backgroundProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
    }

    private fun drawCameraQuad(backgroundProgram: Int, textureTarget: Int, textureId: Int) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)
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
        if (!hasStableLipAnchor) {
            val vertices = face.meshVertices
            val requiredFloats =
                (maxOf(UPPER_INNER_LIP_INDEX, LOWER_INNER_LIP_INDEX) + 1) * 3
            if (vertices.limit() < requiredFloats) return null
            repeat(3) { axis ->
                stableLipAnchorLocal[axis] = (
                    vertices[UPPER_INNER_LIP_INDEX * 3 + axis] +
                        vertices[LOWER_INNER_LIP_INDEX * 3 + axis]
                    ) * 0.5f
            }
            hasStableLipAnchor = true
            anchorTransport.reset()
            lipContourRefiner.reset()
            lipFrameDiagnostics.reset()
            lastLipMetricsTimestampNs = 0L
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "Captured stable face-local lip anchor %.4f,%.4f,%.4f",
                    stableLipAnchorLocal[0],
                    stableLipAnchorLocal[1],
                    stableLipAnchorLocal[2],
                ),
            )
        }

        val x = stableLipAnchorLocal[0]
        val y = stableLipAnchorLocal[1]
        val z = stableLipAnchorLocal[2]

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
        face: AugmentedFace?,
        lipVisible: Boolean,
        pitchRadians: Float?,
        observation: ArCoreMediaPipeLipTracker.Observation,
        bufferedCameraAgeMs: Float,
        bufferedCameraTextureId: Int? = null,
    ): HybridStatus? {
        val faceObservation = observation.face
        val lips = faceObservation.lips
        if (lips.outer.pointCount != LipLandmarkTopology.outerContour.size) return null
        if (lips.inner.pointCount != LipLandmarkTopology.innerContour.size) return null
        val inputWidth = faceObservation.sourceWidth
        val inputHeight = faceObservation.sourceHeight

        val imageTransform = NormalizedImageTransform(
            rotationDegrees = faceObservation.rotationDegrees,
            mirrorHorizontal = faceObservation.mirrorHorizontal,
        )
        val fillTransform = FillCenterTransform.calculate(
            viewWidth = viewportWidth,
            viewHeight = viewportHeight,
            sourceWidth = inputWidth,
            sourceHeight = inputHeight,
        )
        val anchorCorrection = anchorTransport.correctionFor(
            measurementTimestampNs = faceObservation.sensorTimestampNs,
            renderTimestampNs = cameraTimestampNs,
        )
        val translationX = anchorCorrection?.translationX ?: 0f
        val translationY = anchorCorrection?.translationY ?: 0f

        fun writeLandmark(
            normalizedContour: FloatArray,
            pointIndex: Int,
            mediaPipeOnlyPoints: FloatArray,
            points: FloatArray,
            vertices: FloatBuffer,
        ) {
            val coordinateIndex = pointIndex * 2
            val rawX = normalizedContour[coordinateIndex]
            val rawY = normalizedContour[coordinateIndex + 1]
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayY = imageTransform.mapY(rawX, rawY)
            val mediaPipeOnlyX = fillTransform.mapX(displayX, inputWidth) / viewportWidth
            val mediaPipeOnlyY = fillTransform.mapY(displayY, inputHeight) / viewportHeight
            val screenX = mediaPipeOnlyX + translationX
            val screenY = mediaPipeOnlyY + translationY
            val outputIndex = pointIndex * 2
            mediaPipeOnlyPoints[outputIndex] = mediaPipeOnlyX
            mediaPipeOnlyPoints[outputIndex + 1] = mediaPipeOnlyY
            points[outputIndex] = screenX
            points[outputIndex + 1] = screenY
            vertices
                .put(screenX * 2f - 1f)
                .put(1f - screenY * 2f)
                .put(0f)
        }

        mappedMediaPipeOuterLipVertices.clear()
        repeat(lips.outer.pointCount) { pointIndex ->
            writeLandmark(
                lips.outer.points,
                pointIndex,
                mediaPipeOnlyOuterContourPoints,
                measuredOuterContourPoints,
                mappedMediaPipeOuterLipVertices,
            )
        }
        mappedMediaPipeOuterLipVertices.position(0)

        mappedMediaPipeInnerLipVertices.clear()
        repeat(lips.inner.pointCount) { pointIndex ->
            writeLandmark(
                lips.inner.points,
                pointIndex,
                mediaPipeOnlyInnerContourPoints,
                measuredInnerContourPoints,
                mappedMediaPipeInnerLipVertices,
            )
        }
        mappedMediaPipeInnerLipVertices.position(0)
        val refinement = lipContourRefiner.refine(
            measuredOuter = measuredOuterContourPoints,
            measuredInner = measuredInnerContourPoints,
            sensorTimestampNs = faceObservation.sensorTimestampNs,
            renderTimestampNs = cameraTimestampNs,
            geometryConfidence = lips.geometryConfidence,
            mouthOpenness = lips.mouthOpenness,
            outputOuter = outerContourPoints,
            outputInner = innerContourPoints,
        )
        val transition = refinement.diagnostics
        if (faceObservation.sensorTimestampNs != lastLipMetricsTimestampNs) {
            Log.i(
                LIP_METRICS_TAG,
                String.format(
                    Locale.US,
                    "ts=%d mouth=%.3f mouthV=%.2f/s response=%.2f upperR=%.2f " +
                        "lowerR=%.2f/%.2f " +
                        "upperCenter=%+.2f->%+.2f%% upperThickness=%+.2f->%+.2f%% " +
                        "lowerCenter=%+.2f->%+.2f%% lowerThickness=%+.2f->%+.2f%% " +
                        "anchorY=%+.1fpx",
                    faceObservation.sensorTimestampNs,
                    lips.mouthOpenness,
                    transition.mouthOpennessSpeed,
                    refinement.response,
                    transition.upperVerticalResponse,
                    transition.lowerVerticalResponse,
                    transition.lowerThicknessResponse,
                    transition.rawUpperCenterDelta * 100f,
                    transition.filteredUpperCenterDelta * 100f,
                    transition.rawUpperThicknessDelta * 100f,
                    transition.filteredUpperThicknessDelta * 100f,
                    transition.rawLowerCenterDelta * 100f,
                    transition.filteredLowerCenterDelta * 100f,
                    transition.rawLowerThicknessDelta * 100f,
                    transition.filteredLowerThicknessDelta * 100f,
                    translationY * viewportHeight,
                ),
            )
            lastLipMetricsTimestampNs = faceObservation.sensorTimestampNs
        }

        val openInnerCarrierBlend = openInnerCarrierBlend(lips.mouthOpenness)
        val tessellated = lipTessellator.tessellate(
            outerContour = outerContourPoints,
            innerContour = innerContourPoints,
            upperProfile = ReferenceMatteLipstickProfile.upper,
            lowerProfile = ReferenceMatteLipstickProfile.lower,
            outerCarrierExpansion = OUTER_LIP_CARRIER_EXPANSION,
            innerSeamExpansion = CLOSED_INNER_SEAM_EXPANSION +
                (OPEN_INNER_EDGE_EXPANSION - CLOSED_INNER_SEAM_EXPANSION) * openInnerCarrierBlend,
            innerCarrierFeather = openInnerCarrierBlend,
        )
        if (Log.isLoggable(LIP_FRAME_TRACE_TAG, Log.DEBUG)) {
            val frameTrace = lipFrameDiagnostics.capture(
                sensorTimestampNs = faceObservation.sensorTimestampNs,
                renderTimestampNs = cameraTimestampNs,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                mouthOpenness = lips.mouthOpenness,
                anchorCorrection = anchorCorrection,
                mediaPipeOuter = mediaPipeOnlyOuterContourPoints,
                mediaPipeInner = mediaPipeOnlyInnerContourPoints,
                correctedOuter = measuredOuterContourPoints,
                correctedInner = measuredInnerContourPoints,
                refinedOuter = outerContourPoints,
                refinedInner = innerContourPoints,
                tessellated = tessellated,
                tessellator = lipTessellator,
            )
            Log.d(LIP_FRAME_TRACE_TAG, lipFrameDiagnostics.toLogLine(frameTrace))
        }
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
        if (lipVisible) drawLipMesh(lips.semantics, bufferedCameraTextureId)

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

        if (DRAW_DIAGNOSTIC_POINTS && face != null) {
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

        }

        val observationAgeNs = (cameraTimestampNs - faceObservation.sensorTimestampNs)
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
            bufferedCameraAgeMs = bufferedCameraAgeMs,
            sameFrameSynchronized = sameFrameSynchronizationEnabled &&
                faceObservation.sensorTimestampNs == cameraTimestampNs,
            anchorTranslationPixels = anchorTranslationPixels,
            anchorCorrectionAvailable = anchorCorrection != null,
            pitchDegrees = pitchRadians?.let { Math.toDegrees(it.toDouble()).toFloat() },
            lipVisible = lipVisible,
            geometryConfidence = lips.geometryConfidence,
            semanticConfidence = lips.semantics.lipConfidence,
            refinementResponse = refinement.response,
            localMotion = refinement.localMotion,
            mouthOpenness = lips.mouthOpenness,
            mouthOpennessSpeed = transition.mouthOpennessSpeed,
            upperVerticalResponse = transition.upperVerticalResponse,
            rawUpperCenterDelta = transition.rawUpperCenterDelta,
            filteredUpperCenterDelta = transition.filteredUpperCenterDelta,
            rawUpperThicknessDelta = transition.rawUpperThicknessDelta,
            filteredUpperThicknessDelta = transition.filteredUpperThicknessDelta,
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

    private fun drawLipMesh(
        semantics: LipSemanticState,
        bufferedCameraTextureId: Int? = null,
    ) {
        if (lipstickFinish == LipstickFinish.TRACKING_TEST) {
            drawLipMeshPass(semantics, bufferedCameraTextureId, encodeBlurMaterial = false)
            return
        }
        drawBlurredLipMesh(semantics, bufferedCameraTextureId)
    }

    private fun drawBlurredLipMesh(
        semantics: LipSemanticState,
        bufferedCameraTextureId: Int?,
    ) {
        lipBlurFrames.ensureSize(viewportWidth, viewportHeight)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(SIGNED_COLOR_ZERO, SIGNED_COLOR_ZERO, SIGNED_COLOR_ZERO, 0f)
        updateLipBlurRegion()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            lipBlurScissor[0],
            lipBlurScissor[1],
            lipBlurScissor[2],
            lipBlurScissor[3],
        )

        lipBlurFrames.bindSource()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawLipMeshPass(semantics, bufferedCameraTextureId, encodeBlurMaterial = true)

        lipBlurFrames.bindIntermediate()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawLipBlurPass(lipBlurFrames.sourceTextureId, lipBlurStep[0], 0f)

        lipBlurFrames.bindSource()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawLipBlurPass(lipBlurFrames.intermediateTextureId, 0f, lipBlurStep[1])

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        drawLipComposite(lipBlurFrames.sourceTextureId, bufferedCameraTextureId)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun openInnerCarrierBlend(mouthOpenness: Float): Float {
        val transition = ((mouthOpenness - INNER_SEAM_CLOSED_START) /
            (INNER_SEAM_OPEN_END - INNER_SEAM_CLOSED_START)).coerceIn(0f, 1f)
        return transition * transition * (3f - 2f * transition)
    }
    private fun updateLipBlurRegion() {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (index in outerContourPoints.indices step 2) {
            minX = minOf(minX, outerContourPoints[index])
            maxX = maxOf(maxX, outerContourPoints[index])
            minY = minOf(minY, outerContourPoints[index + 1])
            maxY = maxOf(maxY, outerContourPoints[index + 1])
        }
        val spatialBlurScale = BASE_SPATIAL_BLUR_SCALE +
            lipstickTuning.edgeBlur.coerceIn(0f, 1f) * ADDITIONAL_SPATIAL_BLUR_SCALE
        lipBlurStep[0] = ((maxX - minX) / IOS_BLUR_REFERENCE_WIDTH * spatialBlurScale)
            .coerceIn(0.5f / viewportWidth, 2f / viewportWidth)
        lipBlurStep[1] = ((maxY - minY) / IOS_BLUR_REFERENCE_HEIGHT * spatialBlurScale)
            .coerceIn(0.5f / viewportHeight, 2f / viewportHeight)
        val margin = (
            maxOf(
                lipBlurStep[0] * viewportWidth,
                lipBlurStep[1] * viewportHeight,
            ) * 6f + 3f
            ).roundToInt()
        val left = ((minX * viewportWidth).roundToInt() - margin).coerceIn(0, viewportWidth - 1)
        val right = ((maxX * viewportWidth).roundToInt() + margin).coerceIn(left + 1, viewportWidth)
        val bottom = (((1f - maxY) * viewportHeight).roundToInt() - margin)
            .coerceIn(0, viewportHeight - 1)
        val top = (((1f - minY) * viewportHeight).roundToInt() + margin)
            .coerceIn(bottom + 1, viewportHeight)
        lipBlurScissor[0] = left
        lipBlurScissor[1] = bottom
        lipBlurScissor[2] = right - left
        lipBlurScissor[3] = top - bottom
    }

    private fun drawLipBlurPass(textureId: Int, stepX: Float, stepY: Float) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(lipBlurProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(lipBlurProgram, "uSource"), 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(lipBlurProgram, "uTexelStep"),
            stepX,
            stepY,
        )
        FULLSCREEN_QUAD.position(0)
        val position = GLES20.glGetAttribLocation(lipBlurProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, FULLSCREEN_QUAD)
        framebufferUvs.position(0)
        val texCoord = GLES20.glGetAttribLocation(lipBlurProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, framebufferUvs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun drawLipComposite(
        blurredTextureId: Int,
        bufferedCameraTextureId: Int?,
    ) {
        val program = if (bufferedCameraTextureId != null) {
            pairedLipCompositeProgram
        } else {
            lipCompositeProgram
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            if (bufferedCameraTextureId != null) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            bufferedCameraTextureId ?: cameraTextureId,
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCamera"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurredTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uBlurredLip"), 1)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvBottomLeft"), cameraUvCorners[0], cameraUvCorners[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvBottomRight"), cameraUvCorners[2], cameraUvCorners[3])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvTopLeft"), cameraUvCorners[4], cameraUvCorners[5])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUvTopRight"), cameraUvCorners[6], cameraUvCorners[7])
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uOuterFeatherFraction"),
            BASE_OUTER_FEATHER_FRACTION +
                lipstickTuning.edgeBlur.coerceIn(0f, 1f) * ADDITIONAL_OUTER_FEATHER_FRACTION,
        )

        val strideBytes = LipMeshTessellator.VERTEX_COMPONENT_COUNT * Float.SIZE_BYTES
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        tessellatedLipVertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position, 2, GLES20.GL_FLOAT, false, strideBytes, tessellatedLipVertices,
        )
        val lipUv = GLES20.glGetAttribLocation(program, "aLipUv")
        tessellatedLipVertices.position(6)
        GLES20.glEnableVertexAttribArray(lipUv)
        GLES20.glVertexAttribPointer(
            lipUv, 2, GLES20.GL_FLOAT, false, strideBytes, tessellatedLipVertices,
        )
        tessellatedLipIndices.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            lipTessellator.indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            tessellatedLipIndices,
        )
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(lipUv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawLipMeshPass(
        semantics: LipSemanticState,
        bufferedCameraTextureId: Int?,
        encodeBlurMaterial: Boolean,
    ) {
        val lipMeshProgram = if (bufferedCameraTextureId != null) pairedLipMeshProgram else this.lipMeshProgram
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(lipMeshProgram)
        val tuning = lipstickTuning
        val parameters = IosLipMaterial.parameters(lipstickFinish, tuning.productTexture)
        val pigmentBrightness = tuning.pigmentBrightness
        val pigmentRed = if (lipstickFinish == LipstickFinish.TRACKING_TEST) {
            TRACKING_PIGMENT[0]
        } else {
            (tuning.pigmentRed * pigmentBrightness).coerceIn(0f, 1f)
        }
        val pigmentGreen = if (lipstickFinish == LipstickFinish.TRACKING_TEST) {
            TRACKING_PIGMENT[1]
        } else {
            (tuning.pigmentGreen * pigmentBrightness).coerceIn(0f, 1f)
        }
        val pigmentBlue = if (lipstickFinish == LipstickFinish.TRACKING_TEST) {
            TRACKING_PIGMENT[2]
        } else {
            (tuning.pigmentBlue * pigmentBrightness).coerceIn(0f, 1f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            if (bufferedCameraTextureId != null) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            bufferedCameraTextureId ?: cameraTextureId,
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(lipMeshProgram, "uCamera"), 0)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uEncodeBlurMaterial"),
            if (encodeBlurMaterial) 1f else 0f,
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvBottomLeft"), cameraUvCorners[0], cameraUvCorners[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvBottomRight"), cameraUvCorners[2], cameraUvCorners[3])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvTopLeft"), cameraUvCorners[4], cameraUvCorners[5])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uUvTopRight"), cameraUvCorners[6], cameraUvCorners[7])
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uPigment"),
            pigmentRed,
            pigmentGreen,
            pigmentBlue,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uCoverageMultiplier"),
            (if (lipstickFinish == LipstickFinish.TRACKING_TEST) 4f else 2f) * tuning.coverage)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uColorRenderingMode"),
            if (colorRenderingMode == LipColorRenderingMode.UNIFIED) 1f else 0f,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uIosDetailStrength"), parameters.detailStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uIosHighlightStrength"), parameters.highlightStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uIosHighlightMaximum"), parameters.highlightMaximum)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uIosHighlightConcentration"), parameters.highlightConcentration)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uIosCoreCoverage"), tuning.productDensity.pigmentCoverage)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uSemanticConfidence"),
            semantics.lipConfidence,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uSemanticEdgeStrength"),
            semantics.edgeRefinementStrength * tuning.edgeRefinement,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uFinishMode"),
            when (lipstickFinish) {
                LipstickFinish.MATTE -> 0f
                LipstickFinish.GLOSS -> 1f
                LipstickFinish.SATIN -> 2f
                LipstickFinish.TRACKING_TEST -> -1f
            },
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(lipMeshProgram, "uIlluminationSampleStep"),
            ILLUMINATION_SAMPLE_RADIUS_PIXELS * tuning.cameraSampleScale / viewportWidth,
            ILLUMINATION_SAMPLE_RADIUS_PIXELS * tuning.cameraSampleScale / viewportHeight,
        )

        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningOpacity"), tuning.opacity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningPigmentOpacity"), tuning.pigmentOpacity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningBrightness"), tuning.brightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningContrast"), tuning.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningSaturation"), tuning.saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningHueDegrees"), tuning.hueDegrees)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningNaturalLipBlend"), tuning.naturalLipBlend)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningCameraValueTransfer"), tuning.cameraValueTransfer)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningCameraDetail"), tuning.cameraDetail)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningMaterialDetail"), tuning.materialDetail)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningShadowStrength"), tuning.shadowStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningHighlightStrength"), tuning.highlightStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningHighlightSize"), tuning.highlightSize)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningHighlightThreshold"), tuning.highlightThreshold)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningHighlightConcentration"), tuning.highlightConcentration)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningEdgeSoftness"), tuning.edgeSoftness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningEdgeBlur"), tuning.edgeBlur)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningInnerCoverage"), tuning.innerCoverage)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningCornerFade"), tuning.cornerFade)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningSeamShadow"), tuning.seamShadow)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningToothProtection"), tuning.toothProtection)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningCameraSampleScale"), tuning.cameraSampleScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningLuminancePreservation"), tuning.luminancePreservation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningMicroTexture"), tuning.microTexture)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningSurfaceDetail"), tuning.surfaceDetail)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningRoughness"), tuning.roughness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningSpecular"), tuning.specular)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningHighlightRetention"), tuning.highlightRetention)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningSatinGlow"), tuning.satinGlow)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uTuningWetInnerEdge"), tuning.wetInnerEdge)

        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uSatinSourceTexelX"),
            satinSourceTexels[0], satinSourceTexels[1])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lipMeshProgram, "uSatinSourceTexelY"),
            satinSourceTexels[2], satinSourceTexels[3])
        // iOS aperture visibility measures inner opening / mouth width in display pixels.
        val widthX = (outerContourPoints[20] - outerContourPoints[0]) * viewportWidth
        val widthY = (outerContourPoints[21] - outerContourPoints[1]) * viewportHeight
        val width = kotlin.math.hypot(widthX, widthY).coerceAtLeast(1f)
        var minAcross = Float.POSITIVE_INFINITY
        var maxAcross = Float.NEGATIVE_INFINITY
        for (point in innerContourPoints.indices step 2) {
            val across = (innerContourPoints[point] * viewportWidth * -widthY +
                innerContourPoints[point + 1] * viewportHeight * widthX) / width
            minAcross = minOf(minAcross, across)
            maxAcross = maxOf(maxAcross, across)
        }
        val opening = (((maxAcross - minAcross) / width - 0.018f) / (0.055f - 0.018f)).coerceIn(0f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lipMeshProgram, "uSatinApertureVisibility"),
            opening * opening * (3f - 2f * opening))
        val satinUv = GLES20.glGetAttribLocation(lipMeshProgram, "aSatinUv")
        satinUvs.position(0)
        GLES20.glEnableVertexAttribArray(satinUv)
        GLES20.glVertexAttribPointer(satinUv, 2, GLES20.GL_FLOAT, false, 0, satinUvs)
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
        GLES20.glDisableVertexAttribArray(lipUv)
        GLES20.glDisableVertexAttribArray(satinUv)
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
        syncFrameOutcome: SyncFrameOutcome,
    ) {
        if (timestampNs == 0L) return
        if (statusWindowStartNs == 0L) statusWindowStartNs = timestampNs
        statusWindowFrames++
        when (syncFrameOutcome) {
            SyncFrameOutcome.INFERRED -> statusWindowSyncInferredFrames++
            SyncFrameOutcome.REUSED -> statusWindowSyncReusedFrames++
            SyncFrameOutcome.MISSING -> statusWindowSyncMissingFrames++
            SyncFrameOutcome.NOT_ENABLED -> Unit
        }
        val durationNs = timestampNs - statusWindowStartNs
        if (durationNs < STATUS_INTERVAL_NS) return

        val fps = (statusWindowFrames - 1).coerceAtLeast(0) * 1_000_000_000.0 / durationNs
        val pointLabel = screenPoint?.let { point ->
            "point ${(point.first * viewportWidth).roundToInt()},${(point.second * viewportHeight).roundToInt()}"
        } ?: "point —"
        val syncWindowLabel = if (sameFrameSynchronizationEnabled) {
            " · sync $statusWindowSyncInferredFrames/$statusWindowSyncReusedFrames/" +
                statusWindowSyncMissingFrames
        } else {
            ""
        }
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
                "MediaPipe %.1f FPS · YUV %.0f + ML %.0f ms · age %.0f ms · %s · %s · " +
                    "pitch %s · shape %.2f · semantic %.2f · " +
                    "refine %.2f/%.2f · mouth %.2f/%.2f s^-1 · %s\n" +
                    "upper center %+.2f -> %+.2f%% · thickness %+.2f -> %+.2f%% · upperR %.2f",
                status.mediaPipeFps,
                status.conversionDurationMs,
                status.inferenceDurationMs,
                status.cameraAgeMs,
                if (status.sameFrameSynchronized) {
                    String.format(Locale.US, "SYNC pair · camera lag %.0f ms", status.bufferedCameraAgeMs)
                } else {
                    "ASYNC 60 Hz"
                },
                anchorLabel,
                pitchLabel,
                status.geometryConfidence,
                status.semanticConfidence,
                status.refinementResponse,
                status.localMotion,
                status.mouthOpenness,
                status.mouthOpennessSpeed,
                visibilityLabel,
                status.rawUpperCenterDelta * 100f,
                status.filteredUpperCenterDelta * 100f,
                status.rawUpperThicknessDelta * 100f,
                status.filteredUpperThicknessDelta * 100f,
                status.upperVerticalResponse,
            )
        } ?: if (sameFrameSynchronizationEnabled) {
            "MediaPipe: SYNC pair pending, no face or expired"
        } else {
            "MediaPipe: waiting for asynchronous result"
        }
        val text = String.format(
            Locale.US,
            "%s\nARCore %.1f FPS · %s%s\n%s\nanchored tessellated lipstick",
            state,
            fps,
            pointLabel,
            syncWindowLabel,
            hybridLabel,
        )
        onStatus(text)
        Log.d(TAG, text.replace('\n', ' '))
        statusWindowStartNs = timestampNs
        statusWindowFrames = 0
        resetSyncStatusWindow()
    }

    private fun resetSyncStatusWindow() {
        statusWindowSyncInferredFrames = 0
        statusWindowSyncReusedFrames = 0
        statusWindowSyncMissingFrames = 0
    }

    private data class HybridStatus(
        val mediaPipeFps: Float,
        val inferenceDurationMs: Float,
        val conversionDurationMs: Float,
        val cameraAgeMs: Float,
        val bufferedCameraAgeMs: Float,
        val sameFrameSynchronized: Boolean,
        val anchorTranslationPixels: Float,
        val anchorCorrectionAvailable: Boolean,
        val pitchDegrees: Float?,
        val lipVisible: Boolean,
        val geometryConfidence: Float,
        val semanticConfidence: Float,
        val refinementResponse: Float,
        val localMotion: Float,
        val mouthOpenness: Float,
        val mouthOpennessSpeed: Float,
        val upperVerticalResponse: Float,
        val rawUpperCenterDelta: Float,
        val filteredUpperCenterDelta: Float,
        val rawUpperThicknessDelta: Float,
        val filteredUpperThicknessDelta: Float,
    )

    private enum class SyncFrameOutcome {
        NOT_ENABLED,
        INFERRED,
        REUSED,
        MISSING,
    }

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
        const val LIP_METRICS_TAG = "LipJumpMetrics"
        const val LIP_FRAME_TRACE_TAG = "LipFrameTrace"
        const val UPPER_INNER_LIP_INDEX = 13
        const val LOWER_INNER_LIP_INDEX = 14
        const val FACE_LOSS_RESET_FRAME_COUNT = 8
        const val NEAR_METERS = 0.05f
        const val FAR_METERS = 100f
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        // A failure bound, not a smoothing coefficient. Never freeze an old face indefinitely.
        const val MAX_PAIR_AGE_NS = 500_000_000L
        const val DRAW_DIAGNOSTIC_POINTS = false
        const val ILLUMINATION_SAMPLE_RADIUS_PIXELS = 14f
        const val IOS_BLUR_REFERENCE_WIDTH = 224f
        const val IOS_BLUR_REFERENCE_HEIGHT = 112f
        const val SIGNED_COLOR_ZERO = 128f / 255f
        const val BASE_SPATIAL_BLUR_SCALE = 1.50f
        const val ADDITIONAL_SPATIAL_BLUR_SCALE = 1.00f
        const val BASE_OUTER_FEATHER_FRACTION = 0.25f
        const val ADDITIONAL_OUTER_FEATHER_FRACTION = 0.20f
        const val OUTER_LIP_CARRIER_EXPANSION = 0.18f
        const val CLOSED_INNER_SEAM_EXPANSION = 0.14f
        const val OPEN_INNER_EDGE_EXPANSION = 0.10f
        const val INNER_SEAM_CLOSED_START = 0.035f
        const val INNER_SEAM_OPEN_END = 0.12f

        val BUFFERED_CAMERA_UVS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun bufferedCameraShader(source: String): String = source
            .replace("#extension GL_OES_EGL_image_external : require", "")
            .replace("samplerExternalOES", "sampler2D")

        val MAGENTA = floatArrayOf(1f, 0.05f, 0.8f)
        val CYAN = floatArrayOf(0.05f, 1f, 0.95f)
        val DARK_CYAN = floatArrayOf(0f, 0.25f, 0.25f)
        val YELLOW = floatArrayOf(1f, 0.9f, 0.05f)
        val DARK_YELLOW = floatArrayOf(0.3f, 0.2f, 0f)
        val GREEN = floatArrayOf(0.1f, 1f, 0.15f)
        val DARK_GREEN = floatArrayOf(0f, 0.25f, 0.02f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
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
            attribute float aCoverage;
            attribute vec2 aLipUv;
            attribute vec2 aSatinUv;
            varying vec2 vDisplayUv;
            varying float vCoverage;
            varying vec2 vLipUv;
            varying vec2 vSatinUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vDisplayUv = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
                vCoverage = aCoverage;
                vLipUv = aLipUv;
                vSatinUv = aSatinUv;
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
            uniform float uFinishMode;
            uniform float uColorRenderingMode;
            uniform float uIosDetailStrength;
            uniform float uIosHighlightStrength;
            uniform float uIosHighlightMaximum;
            uniform float uIosHighlightConcentration;
            uniform float uIosCoreCoverage;
            uniform vec2 uSatinSourceTexelX;
            uniform vec2 uSatinSourceTexelY;
            uniform float uSatinApertureVisibility;
            uniform vec2 uIlluminationSampleStep;
            uniform float uSemanticConfidence;
            uniform float uSemanticEdgeStrength;
            uniform float uEncodeBlurMaterial;
            uniform float uTuningOpacity;
            uniform float uTuningPigmentOpacity;
            uniform float uTuningBrightness;
            uniform float uTuningContrast;
            uniform float uTuningSaturation;
            uniform float uTuningHueDegrees;
            uniform float uTuningNaturalLipBlend;
            uniform float uTuningCameraValueTransfer;
            uniform float uTuningCameraDetail;
            uniform float uTuningMaterialDetail;
            uniform float uTuningShadowStrength;
            uniform float uTuningHighlightStrength;
            uniform float uTuningHighlightSize;
            uniform float uTuningHighlightThreshold;
            uniform float uTuningHighlightConcentration;
            uniform float uTuningEdgeSoftness;
            uniform float uTuningEdgeBlur;
            uniform float uTuningInnerCoverage;
            uniform float uTuningCornerFade;
            uniform float uTuningSeamShadow;
            uniform float uTuningToothProtection;
            uniform float uTuningCameraSampleScale;
            uniform float uTuningLuminancePreservation;
            uniform float uTuningMicroTexture;
            uniform float uTuningSurfaceDetail;
            uniform float uTuningRoughness;
            uniform float uTuningSpecular;
            uniform float uTuningHighlightRetention;
            uniform float uTuningSatinGlow;
            uniform float uTuningWetInnerEdge;
            varying vec2 vDisplayUv;
            varying float vCoverage;
            varying vec2 vLipUv;
            varying vec2 vSatinUv;

            vec2 cameraUv(vec2 displayUv) {
                vec2 top = mix(uUvTopLeft, uUvTopRight, displayUv.x);
                vec2 bottom = mix(uUvBottomLeft, uUvBottomRight, displayUv.x);
                return mix(top, bottom, displayUv.y);
            }

            vec3 cameraSrgbAt(vec2 displayUv) {
                vec2 uv = cameraUv(clamp(displayUv, vec2(0.0), vec2(1.0)));
                return texture2D(uCamera, uv).rgb;
            }

            float parityLuminance(vec3 color) {
                return clamp(dot(color, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
            }

            vec3 parityColorWithLuminance(vec3 color, float targetLuminance) {
                float sourceLuminance = max(parityLuminance(color), 0.0001);
                vec3 adjusted = color * (targetLuminance / sourceLuminance);
                float maximum = max(adjusted.r, max(adjusted.g, adjusted.b));
                if (maximum > 1.0) {
                    adjusted /= maximum;
                }
                return clamp(adjusted, vec3(0.0), vec3(1.0));
            }

            vec3 lipRgbToHsv(vec3 color) {
                float maximum = max(color.r, max(color.g, color.b));
                float minimum = min(color.r, min(color.g, color.b));
                float delta = maximum - minimum;
                float hue = 0.0;
                if (delta > 0.0001) {
                    if (maximum == color.r) {
                        hue = mod((color.g - color.b) / delta, 6.0);
                    } else if (maximum == color.g) {
                        hue = (color.b - color.r) / delta + 2.0;
                    } else {
                        hue = (color.r - color.g) / delta + 4.0;
                    }
                    hue = fract(hue / 6.0);
                }
                float saturation = maximum > 0.0001 ? delta / maximum : 0.0;
                return vec3(hue, saturation, maximum);
            }

            vec3 lipHsvToRgb(vec3 hsv) {
                float hue = fract(hsv.x) * 6.0;
                float chroma = hsv.z * hsv.y;
                float intermediate = chroma * (1.0 - abs(mod(hue, 2.0) - 1.0));
                vec3 sector;
                if (hue < 1.0) {
                    sector = vec3(chroma, intermediate, 0.0);
                } else if (hue < 2.0) {
                    sector = vec3(intermediate, chroma, 0.0);
                } else if (hue < 3.0) {
                    sector = vec3(0.0, chroma, intermediate);
                } else if (hue < 4.0) {
                    sector = vec3(0.0, intermediate, chroma);
                } else if (hue < 5.0) {
                    sector = vec3(intermediate, 0.0, chroma);
                } else {
                    sector = vec3(chroma, 0.0, intermediate);
                }
                return sector + vec3(hsv.z - chroma);
            }

            vec3 cameraValuePigment(vec3 camera, vec3 pigment) {
                vec3 cameraHsv = lipRgbToHsv(clamp(camera, 0.0, 1.0));
                vec3 pigmentHsv = lipRgbToHsv(clamp(pigment, 0.0, 1.0));
                float pigmentValueScale = pigmentHsv.z / 0.85;
                float transferredValue = clamp(cameraHsv.z * pigmentValueScale, 0.0, 1.0);
                vec3 transferred = lipHsvToRgb(vec3(
                    pigmentHsv.x,
                    pigmentHsv.y,
                    transferredValue
                ));
                return mix(pigment, transferred, clamp(uTuningCameraValueTransfer, 0.0, 1.0));
            }

            vec3 tuneHue(vec3 color, float degrees) {
                float angle = radians(degrees);
                vec3 axis = normalize(vec3(0.299, 0.587, 0.114));
                return color * cos(angle) + cross(axis, color) * sin(angle) +
                    axis * dot(axis, color) * (1.0 - cos(angle));
            }

            vec3 applyLipTuning(vec3 material, vec3 camera) {
                float luminance = parityLuminance(material);
                vec3 tuned = mix(vec3(luminance), material, uTuningSaturation);
                tuned = tuneHue(tuned, uTuningHueDegrees);
                tuned = (tuned - vec3(0.5)) * uTuningContrast + vec3(0.5);
                tuned *= uTuningBrightness;
                tuned = mix(tuned, camera, uTuningNaturalLipBlend);
                return clamp(mix(camera, tuned, uTuningOpacity), 0.0, 1.0);
            }

            vec4 blurMaterialOutput(vec3 composited, vec3 camera, float coverage) {
                if (uEncodeBlurMaterial < 0.5) {
                    return vec4(composited, 1.0);
                }
                float alpha = clamp(coverage * uTuningOpacity, 0.0, 1.0);
                if (alpha < 0.002) {
                    return vec4(vec3(0.5019608), 0.0);
                }
                vec3 delta = clamp((composited - camera) / alpha, -1.0, 1.0);
                return vec4(delta * 0.4980392 + 0.5019608, alpha);
            }

            float tuneCoverage(float coverage) {
                float softened = smoothstep(0.08, 0.92, coverage);
                float tuned = mix(coverage, softened, uTuningEdgeSoftness);
                tuned = pow(max(tuned, 0.0001), mix(1.0, 1.80, uTuningEdgeBlur));
                float innerBand = smoothstep(0.62, 1.0, vLipUv.x);
                return clamp(tuned * mix(1.0, uTuningInnerCoverage, innerBand), 0.0, 1.0);
            }

            ${IosLipMaterial.FRAGMENT_FUNCTION}
            ${UnifiedLipMaterial.FRAGMENT_FUNCTION}

            float semanticRefinedCoverage(float rawCoverage) {
                vec2 edgeStep = uIlluminationSampleStep / 7.0;
                vec3 center = cameraSrgbAt(vDisplayUv);
                vec3 surround =
                    cameraSrgbAt(vDisplayUv + vec2(edgeStep.x, 0.0)) +
                    cameraSrgbAt(vDisplayUv - vec2(edgeStep.x, 0.0)) +
                    cameraSrgbAt(vDisplayUv + vec2(0.0, edgeStep.y)) +
                    cameraSrgbAt(vDisplayUv - vec2(0.0, edgeStep.y));
                surround *= 0.25;
                float cameraEdge = smoothstep(
                    0.018,
                    0.12,
                    length(center - surround)
                );
                float edgeBand = 1.0 - smoothstep(
                    0.18,
                    0.50,
                    abs(rawCoverage - 0.5)
                );
                float thresholdWidth = mix(0.18, 0.09, cameraEdge);
                float sharpened = smoothstep(
                    0.5 - thresholdWidth,
                    0.5 + thresholdWidth,
                    rawCoverage
                );
                float strength = uSemanticConfidence * uSemanticEdgeStrength *
                    cameraEdge * edgeBand;
                float refined = mix(rawCoverage, sharpened, strength);
                // The tessellated mesh excludes the mouth aperture. Do not apply a second
                // opacity fade on the lip side: it creates an unpainted strip at the seam.
                return clamp(refined, 0.0, 1.0);
            }

            void main() {
                vec3 camera = cameraSrgbAt(vDisplayUv);
                float coverage = clamp(vCoverage * uCoverageMultiplier, 0.0, 1.0);
                coverage = tuneCoverage(semanticRefinedCoverage(coverage));
                if (uFinishMode < -0.5) {
                    gl_FragColor = vec4(applyLipTuning(mix(camera, uPigment, coverage), camera), 1.0);
                    return;
                }
                vec4 material = uColorRenderingMode > 0.5 ?
                    renderUnifiedLip(coverage) : renderIosLip(coverage);
                vec3 composited = applyLipTuning(material.rgb, camera);
                gl_FragColor = blurMaterialOutput(composited, camera, material.a);
            }
        """
        const val LIP_BLUR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uSource;
            uniform vec2 uTexelStep;
            varying vec2 vTexCoord;

            void retainStronger(vec4 candidate, inout vec3 color, inout float alpha) {
                if (candidate.a > alpha) {
                    color = candidate.rgb;
                    alpha = candidate.a;
                }
            }

            void main() {
                // Keep material detail/specular at the same pixel through both passes.
                // Neighbor RGB is only needed to extend color into transparent texels.
                vec4 center = texture2D(uSource, vTexCoord);
                vec4 candidate = texture2D(uSource, vTexCoord - uTexelStep * 6.0);
                vec3 retainedColor = center.rgb;
                float retainedAlpha = center.a;
                float blurredAlpha = candidate.a * 0.00735029;
                retainStronger(candidate, retainedColor, retainedAlpha);

                candidate = texture2D(uSource, vTexCoord - uTexelStep * 5.0);
                blurredAlpha += candidate.a * 0.01909834;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord - uTexelStep * 4.0);
                blurredAlpha += candidate.a * 0.04171460;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord - uTexelStep * 3.0);
                blurredAlpha += candidate.a * 0.07659181;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord - uTexelStep * 2.0);
                blurredAlpha += candidate.a * 0.11821653;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord - uTexelStep);
                blurredAlpha += candidate.a * 0.15338247;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = center;
                blurredAlpha += candidate.a * 0.16729190;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord + uTexelStep);
                blurredAlpha += candidate.a * 0.15338247;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord + uTexelStep * 2.0);
                blurredAlpha += candidate.a * 0.11821653;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord + uTexelStep * 3.0);
                blurredAlpha += candidate.a * 0.07659181;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord + uTexelStep * 4.0);
                blurredAlpha += candidate.a * 0.04171460;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord + uTexelStep * 5.0);
                blurredAlpha += candidate.a * 0.01909834;
                retainStronger(candidate, retainedColor, retainedAlpha);
                candidate = texture2D(uSource, vTexCoord + uTexelStep * 6.0);
                blurredAlpha += candidate.a * 0.00735029;
                retainStronger(candidate, retainedColor, retainedAlpha);

                vec3 materialColor = center.a >= 0.002 ? center.rgb : retainedColor;
                gl_FragColor = vec4(materialColor, clamp(blurredAlpha, 0.0, 1.0));
            }
        """

        const val LIP_COMPOSITE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aLipUv;
            varying vec2 vDisplayUv;
            varying vec2 vFramebufferUv;
            varying float vOuterDistance;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vDisplayUv = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
                vFramebufferUv = aPosition * 0.5 + 0.5;
                vOuterDistance = aLipUv.x;
            }
        """

        const val LIP_COMPOSITE_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCamera;
            uniform sampler2D uBlurredLip;
            uniform vec2 uUvBottomLeft;
            uniform vec2 uUvBottomRight;
            uniform vec2 uUvTopLeft;
            uniform vec2 uUvTopRight;
            uniform float uOuterFeatherFraction;
            varying vec2 vDisplayUv;
            varying vec2 vFramebufferUv;
            varying float vOuterDistance;

            vec2 compositeCameraUv(vec2 displayUv) {
                vec2 top = mix(uUvTopLeft, uUvTopRight, displayUv.x);
                vec2 bottom = mix(uUvBottomLeft, uUvBottomRight, displayUv.x);
                return mix(top, bottom, displayUv.y);
            }

            void main() {
                vec3 camera = texture2D(uCamera, compositeCameraUv(vDisplayUv)).rgb;
                vec4 blurredLip = texture2D(uBlurredLip, vFramebufferUv);
                vec3 materialDelta = (blurredLip.rgb - 0.5019608) / 0.4980392;
                float outerFeather = smoothstep(
                    0.0,
                    uOuterFeatherFraction,
                    clamp(vOuterDistance, 0.0, 1.0)
                );
                vec3 composited = camera + materialDelta * blurredLip.a * outerFeather;
                gl_FragColor = vec4(clamp(composited, 0.0, 1.0), 1.0);
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
