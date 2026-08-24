package com.example.armakeup.arcore

import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import com.example.armakeup.render.NativeVulkanBootstrap
import com.example.armakeup.render.NativeVulkanDiagnosticRuntime
import com.example.armakeup.tracking.TrackingGeometryExtractor
import com.example.armakeup.tracking.face.FaceMesh468RegionTopology
import com.example.armakeup.tracking.face.FaceLocalGeometryPerspectivePolicy
import com.example.armakeup.tracking.face.FaceRegion
import com.example.armakeup.tracking.face.FaceSurfaceDepthSampler
import com.example.armakeup.tracking.face.FaceSurfaceTopology
import com.example.armakeup.tracking.face.FullFaceRenderState
import com.example.armakeup.tracking.face.HybridFullFaceStateComposer
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Debug cutover in which ARCore owns the camera and Vulkan presents that exact ARCore frame.
 *
 * Camera pixels, ARCore global pose and the accepted hybrid lip geometry share the frame timestamp.
 * No CameraX camera is opened and no CPU copy is introduced into the visible camera path.
 */
internal class ArCoreVulkanFaceRenderer(
    private val surfaceView: SurfaceView,
    private val faceDepthEnabled: Boolean,
    private val displayRotation: () -> Int,
    private val imageRotationDegrees: () -> Int,
    private val onStatus: (String) -> Unit,
    private val onFatalError: (String) -> Unit,
) : SurfaceHolder.Callback, Choreographer.FrameCallback, AutoCloseable {

    @Volatile
    private var session: Session? = null

    @Volatile
    private var mediaPipeTracker: ArCoreMediaPipeLipTracker? = null

    private val choreographer = Choreographer.getInstance()
    private val nativeProbe = NativeVulkanBootstrap.probe()
    private val lipTessellator = LipMeshTessellator()
    private val nativeLipVertices = FloatArray(lipTessellator.vertexCount * NATIVE_VERTEX_COMPONENTS)
    private val nativeFaceVertices = FloatArray(
        FaceMesh468RegionTopology.POINT_COUNT * FACE_VERTEX_COMPONENTS,
    )
    private val transformedCameraCorners = directFloatBuffer(CAMERA_CORNER_COMPONENTS)
    private val transformedCameraCornerValues = FloatArray(CAMERA_CORNER_COMPONENTS)
    private val cameraUvTransform = FloatArray(UV_TRANSFORM_COMPONENTS)
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
        fullLocalAffineResidual = FULL_LOCAL_AFFINE_RESIDUAL,
        maximumLocalObservationAgeNs = MAXIMUM_LOCAL_OBSERVATION_AGE_NS,
        perspectivePolicy = FaceLocalGeometryPerspectivePolicy.profileSafe(),
    )

    private var runtime: NativeVulkanDiagnosticRuntime? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var resumed = false
    private var frameCallbackPosted = false
    private var closed = false
    private var fatalErrorDelivered = false
    private var lastObservedCameraTimestampNs = 0L
    private var lastAcceptedCameraTimestampNs = 0L
    private var lastValidLipTimestampNs = 0L
    private var lastValidFaceTimestampNs = 0L
    private var depthSamplerTopology: FaceSurfaceTopology? = null
    private var depthSampler: FaceSurfaceDepthSampler? = null
    private var runtimeRecoveryAttempts = 0
    private var lastRenderState: FullFaceRenderState? = null
    private var statusWindowStartNs = 0L
    private var statusWindowFrames = 0

    init {
        surfaceView.holder.addCallback(this)
        Log.i(TAG, "ARCore Vulkan renderer created ${nativeProbe.diagnostic}")
    }

    fun bindSession(value: Session?) {
        session = value
        if (value == null) arCoreObservationAdapter.clear()
    }

    fun bindMediaPipeTracker(value: ArCoreMediaPipeLipTracker?) {
        mediaPipeTracker = value
    }

    fun resume() {
        if (closed) return
        resumed = true
        postFrameCallback()
    }

    fun pause() {
        resumed = false
        removeFrameCallback()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (closed) return
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        runtimeRecoveryAttempts = 0
        replaceRuntime(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        removeFrameCallback()
        runtime?.close()
        runtime = null
        resetPresentationState()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        if (!resumed || closed) return
        try {
            renderFrame()
        } catch (error: CameraNotAvailableException) {
            Log.e(TAG, "ARCore camera became unavailable", error)
            reportFatalError("ARCore camera unavailable: ${error.message ?: "unknown error"}")
            bindSession(null)
        } catch (error: RuntimeException) {
            Log.e(TAG, "ARCore Vulkan render failure", error)
            reportFatalError(
                "ARCore Vulkan render failed: ${error.message ?: error.javaClass.simpleName}",
            )
            bindSession(null)
        } finally {
            postFrameCallback()
        }
    }

    private fun renderFrame() {
        val activeSession = session ?: return
        val activeRuntime = runtime ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val currentDisplayRotation = displayRotation()
        val currentImageRotationDegrees = imageRotationDegrees()
        activeSession.setDisplayGeometry(currentDisplayRotation, viewportWidth, viewportHeight)
        val frame = activeSession.update()
        val timestampNs = frame.timestamp
        if (timestampNs > 0L && timestampNs != lastObservedCameraTimestampNs) {
            lastObservedCameraTimestampNs = timestampNs
            mediaPipeTracker?.tryDetect(frame, currentImageRotationDegrees)
            val renderState = composeRenderState(
                activeSession = activeSession,
                frame = frame,
                timestampNs = timestampNs,
                displayRotation = currentDisplayRotation,
                imageRotationDegrees = currentImageRotationDegrees,
            )
            val retainedTimestampNs = if (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1
            ) {
                importCameraFrame(activeRuntime, frame, timestampNs)
            } else {
                lastAcceptedCameraTimestampNs
            }
            if (retainedTimestampNs == timestampNs) {
                uploadFaceSurface(activeRuntime, renderState, timestampNs)
                uploadLip(activeRuntime, renderState, timestampNs)
                lastRenderState = renderState
                lastAcceptedCameraTimestampNs = timestampNs
            }
        }

        if (lastAcceptedCameraTimestampNs > 0L) {
            if (activeRuntime.presentVisibleFrame() < 0L) {
                recoverRuntimeAfterPresentFailure()
                return
            }
            runtimeRecoveryAttempts = 0
            publishStatus(timestampNs)
        }
    }

    private fun composeRenderState(
        activeSession: Session,
        frame: Frame,
        timestampNs: Long,
        displayRotation: Int,
        imageRotationDegrees: Int,
    ): FullFaceRenderState? {
        val face = activeSession
            .getAllTrackables(AugmentedFace::class.java)
            .firstOrNull { it.trackingState == TrackingState.TRACKING }
        if (face == null) {
            arCoreObservationAdapter.clear()
            return null
        }
        val globalObservationAvailable = arCoreObservationAdapter.create(
            sensorTimestampNs = timestampNs,
            displayRotation = displayRotation,
            analysisImageRotationDegrees = imageRotationDegrees,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            camera = frame.camera,
            face = face,
        ) != null
        if (!globalObservationAvailable) return null
        return stateComposer.compose(
            globalBackend = arCoreObservationAdapter,
            localBackend = mediaPipeTracker,
            renderTimestampNs = timestampNs,
        )
    }

    @RequiresApi(android.os.Build.VERSION_CODES.O_MR1)
    private fun importCameraFrame(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        frame: Frame,
        timestampNs: Long,
    ): Long {
        updateCameraUvTransform(frame)
        return try {
            val hardwareBuffer = frame.hardwareBuffer ?: return lastAcceptedCameraTimestampNs
            hardwareBuffer.use {
                activeRuntime.updateExternalVisibleCamera(
                    hardwareBuffer = hardwareBuffer,
                    sensorTimestampNs = timestampNs,
                    uvTransform = cameraUvTransform,
                )
            }
        } catch (_: NotYetAvailableException) {
            lastAcceptedCameraTimestampNs
        } catch (_: DeadlineExceededException) {
            lastAcceptedCameraTimestampNs
        }
    }

    private fun updateCameraUvTransform(frame: Frame) {
        CAMERA_NDC_CORNERS.position(0)
        transformedCameraCorners.clear()
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            CAMERA_NDC_CORNERS,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedCameraCorners,
        )
        transformedCameraCorners.position(0)
        transformedCameraCorners.get(transformedCameraCornerValues)
        ArCoreVulkanUvTransform.writeFromTextureCorners(
            textureCorners = transformedCameraCornerValues,
            destination = cameraUvTransform,
        )
    }

    private fun uploadLip(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        state: FullFaceRenderState?,
        timestampNs: Long,
    ) {
        val outer = state?.region(FaceRegion.LIPS_OUTER)
        val inner = state?.region(FaceRegion.LIPS_INNER)
        if (outer == null || inner == null ||
            outer.pointCount != LIP_CONTOUR_POINT_COUNT ||
            inner.pointCount != LIP_CONTOUR_POINT_COUNT) {
            if (lastValidLipTimestampNs > 0L &&
                timestampNs - lastValidLipTimestampNs <= TRACKING_LOSS_HOLD_NS
            ) {
                return
            }
            activeRuntime.updateTrackingTestLip(
                vertices = EMPTY_FLOATS,
                indices = EMPTY_SHORTS,
                visible = false,
            )
            lastValidLipTimestampNs = 0L
            return
        }
        val tessellated = lipTessellator.tessellate(
            outerContour = outer.packedCopy(),
            innerContour = inner.packedCopy(),
            upperProfile = ReferenceMatteLipstickProfile.upper,
            lowerProfile = ReferenceMatteLipstickProfile.lower,
        )
        val surfaceDepthSampler = if (faceDepthEnabled) {
            state.surfaceTopology?.let(::depthSamplerFor)
        } else {
            null
        }
        var sourceIndex = 0
        var destinationIndex = 0
        while (sourceIndex < tessellated.size) {
            val x = tessellated[sourceIndex + LipMeshTessellator.X_COMPONENT_OFFSET]
            val y = tessellated[sourceIndex + LipMeshTessellator.Y_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex] = x
            nativeLipVertices[destinationIndex + 1] = y
            nativeLipVertices[destinationIndex + 2] = x
            nativeLipVertices[destinationIndex + 3] = y
            nativeLipVertices[destinationIndex + 4] =
                tessellated[sourceIndex + LipMeshTessellator.COVERAGE_COMPONENT_OFFSET]
            nativeLipVertices[destinationIndex + 5] = surfaceDepthSampler?.ndcDepthAt(
                displayLandmarks = state.displayLandmarks,
                x = x,
                y = y,
            ) ?: DEFAULT_LIP_NDC_DEPTH
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
            destinationIndex += NATIVE_VERTEX_COMPONENTS
        }
        check(
            activeRuntime.updateTrackingTestLip(
                vertices = nativeLipVertices,
                indices = lipTessellator.indices,
                temporalFlowEnabled = false,
                visible = true,
            ),
        ) { "ARCore Vulkan lip upload failed" }
        lastValidLipTimestampNs = timestampNs
    }

    private fun uploadFaceSurface(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        state: FullFaceRenderState?,
        timestampNs: Long,
    ) {
        if (!faceDepthEnabled) {
            activeRuntime.updateFaceOccluder(
                vertices = EMPTY_FLOATS,
                indices = EMPTY_SHORTS,
                visible = false,
            )
            lastValidFaceTimestampNs = 0L
            return
        }
        val display = state?.displayLandmarks
        val topology = state?.surfaceTopology
        if (display == null || topology == null ||
            display.pointCount != FaceMesh468RegionTopology.POINT_COUNT
        ) {
            if (lastValidFaceTimestampNs > 0L &&
                timestampNs - lastValidFaceTimestampNs <= TRACKING_LOSS_HOLD_NS
            ) {
                return
            }
            activeRuntime.updateFaceOccluder(
                vertices = EMPTY_FLOATS,
                indices = EMPTY_SHORTS,
                visible = false,
            )
            lastValidFaceTimestampNs = 0L
            return
        }
        var destination = 0
        repeat(display.pointCount) { index ->
            nativeFaceVertices[destination++] = display.x(index)
            nativeFaceVertices[destination++] = display.y(index)
            nativeFaceVertices[destination++] = display.z(index)
        }
        check(
            activeRuntime.updateFaceOccluder(
                vertices = nativeFaceVertices,
                indices = topology.packedCopy(),
                visible = true,
            ),
        ) { "ARCore Vulkan face-occluder upload failed" }
        lastValidFaceTimestampNs = timestampNs
    }

    private fun depthSamplerFor(topology: FaceSurfaceTopology): FaceSurfaceDepthSampler {
        if (depthSamplerTopology != topology || depthSampler == null) {
            depthSamplerTopology = topology
            depthSampler = FaceSurfaceDepthSampler(
                topology = topology,
                preferredLandmarkIndices =
                    LipLandmarkTopology.outerContour + LipLandmarkTopology.innerContour,
            )
        }
        return checkNotNull(depthSampler)
    }

    private fun publishStatus(timestampNs: Long) {
        if (timestampNs <= 0L) return
        if (statusWindowStartNs == 0L) statusWindowStartNs = timestampNs
        statusWindowFrames++
        val durationNs = timestampNs - statusWindowStartNs
        if (durationNs < STATUS_INTERVAL_NS) return
        val fps = (statusWindowFrames - 1).coerceAtLeast(0) * 1_000_000_000.0 / durationNs
        onStatus(
            String.format(
                Locale.US,
                "ARCore + Vulkan %.1f FPS · depth=%s\n" +
                    "same Frame: camera + ARCore pose + hybrid lips\n" +
                    "lipLocal=%.2f age=%s residual=%s\n%s",
                fps,
                if (faceDepthEnabled) "3D" else "2D",
                lastRenderState?.attachmentQuality?.localDeformationWeight ?: 0f,
                lastRenderState?.attachmentQuality?.localObservationAgeNs
                    ?.let { "${it / 1_000_000L}ms" } ?: "global",
                lastRenderState?.attachmentQuality?.affineFitResidualNormalized
                    ?.let { String.format(Locale.US, "%.4f", it) } ?: "n/a",
                runtime?.diagnostic ?: "Vulkan unavailable",
            ),
        )
        statusWindowStartNs = timestampNs
        statusWindowFrames = 0
    }

    private fun reportFatalError(message: String) {
        if (fatalErrorDelivered) return
        fatalErrorDelivered = true
        onFatalError(message)
    }

    private fun recoverRuntimeAfterPresentFailure() {
        runtimeRecoveryAttempts++
        Log.w(TAG, "Retained-camera present failed; rebuilding swapchain " +
            "attempt=$runtimeRecoveryAttempts")
        if (runtimeRecoveryAttempts > MAXIMUM_RUNTIME_RECOVERY_ATTEMPTS ||
            !surfaceView.holder.surface.isValid
        ) {
            reportFatalError("ARCore Vulkan retained-camera present failed")
            return
        }
        onStatus("ARCore + Vulkan: rebuilding camera presentation")
        replaceRuntime(surfaceView.holder)
    }

    private fun replaceRuntime(holder: SurfaceHolder) {
        removeFrameCallback()
        runtime?.close()
        runtime = null
        resetPresentationState()
        runtime = NativeVulkanDiagnosticRuntime.createVisibleOrNull(
            probe = nativeProbe,
            surface = holder.surface,
            width = viewportWidth,
            height = viewportHeight,
        )
        if (runtime == null) {
            reportFatalError("Failed to create ARCore Vulkan swapchain")
            return
        }
        fatalErrorDelivered = false
        postFrameCallback()
    }

    /** A retained camera texture belongs to exactly one native runtime/swapchain generation. */
    private fun resetPresentationState() {
        lastObservedCameraTimestampNs = 0L
        lastAcceptedCameraTimestampNs = 0L
        lastValidLipTimestampNs = 0L
        lastValidFaceTimestampNs = 0L
        depthSamplerTopology = null
        depthSampler = null
        lastRenderState = null
        statusWindowStartNs = 0L
        statusWindowFrames = 0
        arCoreObservationAdapter.clear()
    }

    private fun postFrameCallback() {
        if (!resumed || closed || frameCallbackPosted || runtime == null || session == null) return
        frameCallbackPosted = true
        choreographer.postFrameCallback(this)
    }

    private fun removeFrameCallback() {
        if (!frameCallbackPosted) return
        choreographer.removeFrameCallback(this)
        frameCallbackPosted = false
    }

    override fun close() {
        if (closed) return
        closed = true
        resumed = false
        removeFrameCallback()
        surfaceView.holder.removeCallback(this)
        runtime?.close()
        runtime = null
        resetPresentationState()
        bindMediaPipeTracker(null)
        bindSession(null)
    }

    private companion object {
        const val TAG = "ARMakeupArCoreVulkan"
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        const val FULL_LOCAL_AFFINE_RESIDUAL = 0.006f
        const val MAXIMUM_GLOBAL_AFFINE_RESIDUAL = 0.016f
        const val MAXIMUM_LOCAL_OBSERVATION_AGE_NS = 120_000_000L
        const val TRACKING_LOSS_HOLD_NS = 100_000_000L
        const val MAXIMUM_RUNTIME_RECOVERY_ATTEMPTS = 1
        const val NATIVE_VERTEX_COMPONENTS = 6
        const val FACE_VERTEX_COMPONENTS = 3
        const val DEFAULT_LIP_NDC_DEPTH = -1f
        const val LIP_CONTOUR_POINT_COUNT = 20
        const val CAMERA_CORNER_COMPONENTS = 6
        const val UV_TRANSFORM_COMPONENTS = 16

        val EMPTY_FLOATS = FloatArray(0)
        val EMPTY_SHORTS = ShortArray(0)
        val CAMERA_NDC_CORNERS: FloatBuffer = directFloatBuffer(
            floatArrayOf(
                -1f, 1f,
                1f, 1f,
                -1f, -1f,
            ),
        )

        fun directFloatBuffer(size: Int): FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        fun directFloatBuffer(values: FloatArray): FloatBuffer = directFloatBuffer(values.size)
            .put(values)
            .apply { position(0) }
    }
}

/** Builds the column-major affine transform consumed by camera_frame.frag. */
internal object ArCoreVulkanUvTransform {
    private const val MATRIX_COMPONENTS = 16
    private const val CORNER_COMPONENTS = 6

    fun writeFromTextureCorners(textureCorners: FloatArray, destination: FloatArray) {
        require(textureCorners.size == CORNER_COMPONENTS)
        require(destination.size == MATRIX_COMPONENTS)
        val originU = textureCorners[0]
        val originV = textureCorners[1]
        val rightU = textureCorners[2]
        val rightV = textureCorners[3]
        val downU = textureCorners[4]
        val downV = textureCorners[5]
        destination.fill(0f)
        destination[0] = rightU - originU
        destination[1] = rightV - originV
        destination[4] = downU - originU
        destination[5] = downV - originV
        destination[10] = 1f
        destination[12] = originU
        destination[13] = originV
        destination[15] = 1f
    }
}
