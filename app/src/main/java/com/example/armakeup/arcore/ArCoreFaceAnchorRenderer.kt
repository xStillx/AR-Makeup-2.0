package com.example.armakeup.arcore

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.tracking.TrackingGeometryExtractor
import com.example.armakeup.tracking.face.FaceRegion
import com.example.armakeup.tracking.face.FaceMesh468RegionTopology
import com.example.armakeup.tracking.face.FullFaceRenderState
import com.example.armakeup.tracking.face.HybridFullFaceStateComposer
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** ARCore camera adapter and debug renderer for the model-independent FF3 render state. */
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
    private var cameraTextureBoundSession: Session? = null

    private val transformedCameraUvs = directFloatBuffer(8)
    private val lipAnchorVertex = directFloatBuffer(3)
    private val mappedLipVertices = directFloatBuffer(
        (LipLandmarkTopology.outerContour.size + LipLandmarkTopology.innerContour.size) * 3,
    )
    private val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val arCoreObservationAdapter = ArCoreFaceObservationAdapter()
    private val stateComposer = HybridFullFaceStateComposer(
        stableAnchorIndices = TrackingGeometryExtractor.stableAnchorIndices,
        outerLipIndices = LipLandmarkTopology.outerContour,
        innerLipIndices = LipLandmarkTopology.innerContour,
        leftEyeIndices = FaceMesh468RegionTopology.leftEyeContour,
        rightEyeIndices = FaceMesh468RegionTopology.rightEyeContour,
        mouthAperture = FaceMesh468RegionTopology.mouthAperture,
        leftEyeAperture = FaceMesh468RegionTopology.leftEyeAperture,
        rightEyeAperture = FaceMesh468RegionTopology.rightEyeAperture,
        maximumGlobalAffineResidual = MAXIMUM_GLOBAL_AFFINE_RESIDUAL,
    )

    private var statusWindowStartNs = 0L
    private var statusWindowFrames = 0

    fun bindSession(value: Session?) {
        session = value
        cameraTextureBoundSession = null
        if (value == null) arCoreObservationAdapter.clear()
    }

    fun bindMediaPipeTracker(value: ArCoreMediaPipeLipTracker?) {
        mediaPipeTracker = value
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        cameraTextureId = createExternalTexture()
        backgroundProgram = createProgram(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER)
        pointProgram = createProgram(POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER)
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
            val currentDisplayRotation = displayRotation()
            val currentImageRotationDegrees = imageRotationDegrees()
            activeSession.setDisplayGeometry(currentDisplayRotation, viewportWidth, viewportHeight)
            val frame = activeSession.update()
            val timestampNs = frame.timestamp

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (timestampNs != 0L) {
                drawCameraBackground(frame)
                mediaPipeTracker?.tryDetect(frame, currentImageRotationDegrees)
            }

            val face = activeSession
                .getAllTrackables(AugmentedFace::class.java)
                .firstOrNull { it.trackingState == TrackingState.TRACKING }
            if (face == null) arCoreObservationAdapter.clear()
            val globalObservationAvailable = face?.let { trackedFace ->
                arCoreObservationAdapter.create(
                    sensorTimestampNs = timestampNs,
                    displayRotation = currentDisplayRotation,
                    analysisImageRotationDegrees = currentImageRotationDegrees,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    camera = frame.camera,
                    face = trackedFace,
                ) != null
            } ?: false
            val renderState = if (globalObservationAvailable) {
                stateComposer.compose(
                    globalBackend = arCoreObservationAdapter,
                    localBackend = mediaPipeTracker,
                    renderTimestampNs = timestampNs,
                )
            } else {
                null
            }
            val screenPoint = renderState?.let(::drawLipAnchor)
            val hybridStatus = renderState?.let(::drawHybridLipContour)
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

    private fun drawLipAnchor(state: FullFaceRenderState): Pair<Float, Float>? {
        val anchor = state.lipAnchor ?: return null
        lipAnchorVertex.position(0)
        lipAnchorVertex
            .put(anchor.x * 2f - 1f)
            .put(1f - anchor.y * 2f)
            .put(0f)
            .position(0)
        drawPointCloud(
            vertices = lipAnchorVertex,
            pointCount = 1,
            transform = identity,
            pointSize = 30f,
            color = MAGENTA,
            ringColor = WHITE,
        )
        return Pair(anchor.x, anchor.y)
    }

    private fun drawHybridLipContour(state: FullFaceRenderState): HybridStatus? {
        val outer = state.region(FaceRegion.LIPS_OUTER) ?: return null
        val inner = state.region(FaceRegion.LIPS_INNER) ?: return null
        mappedLipVertices.clear()
        var pointCount = 0
        fun appendRegion(region: com.example.armakeup.tracking.face.FaceRegionGeometry) {
            repeat(region.pointCount) { pointIndex ->
                val screenX = region.x(pointIndex)
                val screenY = region.y(pointIndex)
                mappedLipVertices
                    .put(screenX * 2f - 1f)
                    .put(1f - screenY * 2f)
                    .put(0f)
                pointCount++
            }
        }
        appendRegion(outer)
        appendRegion(inner)
        mappedLipVertices.position(0)
        drawPointCloud(
            vertices = mappedLipVertices,
            pointCount = pointCount,
            transform = identity,
            pointSize = 11f,
            color = CYAN,
            ringColor = DARK_CYAN,
        )

        val diagnostics = state.localDiagnostics ?: return null
        return HybridStatus(
            mediaPipeFps = diagnostics.smoothedFps,
            inferenceDurationMs = diagnostics.inferenceDurationMs,
            conversionDurationMs = diagnostics.conversionDurationMs,
            cameraAgeMs = (state.attachmentQuality.localObservationAgeNs ?: 0L) /
                1_000_000f,
            fitResidualPixels = (state.attachmentQuality.affineFitResidualNormalized ?: 0f) *
                minOf(viewportWidth, viewportHeight),
            localDeformationApplied = state.attachmentQuality.localDeformationApplied,
        )
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
            String.format(
                Locale.US,
                "MediaPipe %.1f FPS · YUV %.0f + ML %.0f ms · age %.0f ms · fit %.1f px · %s",
                status.mediaPipeFps,
                status.conversionDurationMs,
                status.inferenceDurationMs,
                status.cameraAgeMs,
                status.fitResidualPixels,
                if (status.localDeformationApplied) "local" else "ARCore fallback",
            )
        } ?: "MediaPipe: waiting for synchronized result"
        val text = String.format(
            Locale.US,
            "%s\nARCore %.1f FPS · %s\n%s\nmagenta = ARCore anchor · cyan = hybrid lip shape",
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
        val fitResidualPixels: Float,
        val localDeformationApplied: Boolean,
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
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        const val MAXIMUM_GLOBAL_AFFINE_RESIDUAL = 0.08f

        val MAGENTA = floatArrayOf(1f, 0.05f, 0.8f)
        val CYAN = floatArrayOf(0.05f, 1f, 0.95f)
        val DARK_CYAN = floatArrayOf(0f, 0.25f, 0.25f)
        val WHITE = floatArrayOf(1f, 1f, 1f)

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

        fun directFloatBuffer(size: Int): FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        fun directFloatBuffer(values: FloatArray): FloatBuffer = directFloatBuffer(values.size)
            .put(values)
            .apply { position(0) }
    }
}
