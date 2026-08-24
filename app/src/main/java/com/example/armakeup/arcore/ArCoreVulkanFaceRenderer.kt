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
import com.example.armakeup.tracking.CanonicalFaceTransform
import com.example.armakeup.tracking.TrackingGeometryExtractor
import com.example.armakeup.tracking.face.DynamicLipContourCoverage
import com.example.armakeup.tracking.face.FaceMesh468RegionTopology
import com.example.armakeup.tracking.face.FaceLocalGeometryPerspectivePolicy
import com.example.armakeup.tracking.face.FaceLandmarkSet
import com.example.armakeup.tracking.face.FaceRegion
import com.example.armakeup.tracking.face.FaceSurfaceDepthSampler
import com.example.armakeup.tracking.face.FaceSurfaceLipCoverage
import com.example.armakeup.tracking.face.FaceSurfaceCameraLipDeformer
import com.example.armakeup.tracking.face.FaceSurfaceTopology
import com.example.armakeup.tracking.face.FaceSurfaceTextureCoordinates
import com.example.armakeup.tracking.face.FullFaceRenderState
import com.example.armakeup.tracking.face.HybridFullFaceStateComposer
import com.example.armakeup.tracking.face.TrackingLossEpisodeTracker
import com.example.armakeup.tracking.face.TrackingVisibilityController
import com.example.armakeup.tracking.face.TrackingVisibilityDecision
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
    private val lipDepthBias: Float,
    private val visualizeFaceDepth: Boolean,
    private val visualizeLipDepth: Boolean,
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
    private var faceLipTopology: FaceSurfaceTopology? = null
    private var faceLipIndices = EMPTY_SHORTS
    private var faceLipTextureCoordinates: FaceSurfaceTextureCoordinates? = null
    private var faceLipCoverage = EMPTY_FLOATS
    private val dynamicLipContour = FloatArray(DynamicLipContourCoverage.FLOAT_COUNT)
    private var deformedSurfaceTimestampNs = 0L
    private var deformedSurfaceCoordinates: FloatArray? = null
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
        localObservationAgeFadeOutNs = LOCAL_OBSERVATION_AGE_FADE_OUT_NS,
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
    private val trackingLossEpisodes = TrackingLossEpisodeTracker()
    private val trackingVisibilityController = TrackingVisibilityController()
    private var lastTrackingVisibilityDecision = TrackingVisibilityDecision.HIDDEN
    private var lastLipDepthSamplingStats: LipDepthSamplingStats? = null

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
            trackingLossEpisodes.record(timestampNs, tracking = renderState != null)?.let { episode ->
                Log.i(
                    TAG,
                    "FF5 tracking reacquired after frames=${episode.frameCount} " +
                        "durationMs=${episode.durationNs / 1_000_000L}",
                )
            }
            lastTrackingVisibilityDecision = if (faceDepthEnabled) {
                trackingVisibilityController.update(timestampNs, tracking = renderState != null)
            } else {
                if (renderState != null) {
                    TrackingVisibilityDecision(
                        useCurrentGeometry = true,
                        retainLastGeometry = false,
                        opacity = 1f,
                    )
                } else {
                    TrackingVisibilityDecision.HIDDEN
                }
            }
            val retainedTimestampNs = if (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1
            ) {
                importCameraFrame(activeRuntime, frame, timestampNs)
            } else {
                lastAcceptedCameraTimestampNs
            }
            if (retainedTimestampNs == timestampNs) {
                uploadFaceSurface(
                    activeRuntime,
                    renderState,
                    timestampNs,
                    lastTrackingVisibilityDecision,
                )
                uploadLip(
                    activeRuntime,
                    renderState,
                    timestampNs,
                    lastTrackingVisibilityDecision,
                )
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
        visibility: TrackingVisibilityDecision,
    ) {
        val outer = state?.region(FaceRegion.LIPS_OUTER)
        val inner = state?.region(FaceRegion.LIPS_INNER)
        if (outer == null || inner == null ||
            outer.pointCount != LIP_CONTOUR_POINT_COUNT ||
            inner.pointCount != LIP_CONTOUR_POINT_COUNT) {
            if (faceDepthEnabled && visibility.retainLastGeometry &&
                uploadRetainedFaceAttachedLipOpacity(activeRuntime, visibility.opacity)
            ) {
                return
            }
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
            lastLipDepthSamplingStats = null
            return
        }
        if (faceDepthEnabled &&
            uploadFaceAttachedLip(activeRuntime, state, visibility.opacity)
        ) {
            lastValidLipTimestampNs = timestampNs
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
        var sampledDepthMinimum = Float.POSITIVE_INFINITY
        var sampledDepthMaximum = Float.NEGATIVE_INFINITY
        var triangleSampleCount = 0
        var fallbackSampleCount = 0
        var overlappingSampleCount = 0
        var maximumOverlapSpread = 0f
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
            val depthSample = surfaceDepthSampler?.sampleAt(
                displayLandmarks = state.displayLandmarks,
                x = x,
                y = y,
            )
            val sampledDepth = depthSample?.ndcDepth ?: DEFAULT_LIP_NDC_DEPTH
            nativeLipVertices[destinationIndex + 5] = sampledDepth
            if (depthSample != null) {
                sampledDepthMinimum = minOf(sampledDepthMinimum, sampledDepth)
                sampledDepthMaximum = maxOf(sampledDepthMaximum, sampledDepth)
                if (depthSample.usedNearestLandmarkFallback) {
                    fallbackSampleCount++
                } else {
                    triangleSampleCount++
                }
                if (depthSample.triangleHitCount > 1) overlappingSampleCount++
                maximumOverlapSpread = maxOf(
                    maximumOverlapSpread,
                    depthSample.overlappingDepthSpread,
                )
            }
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
            destinationIndex += NATIVE_VERTEX_COMPONENTS
        }
        lastLipDepthSamplingStats = if (
            surfaceDepthSampler != null && sampledDepthMinimum.isFinite() &&
            sampledDepthMaximum.isFinite()
        ) {
            LipDepthSamplingStats(
                minimumNdcDepth = sampledDepthMinimum,
                maximumNdcDepth = sampledDepthMaximum,
                triangleSampleCount = triangleSampleCount,
                fallbackSampleCount = fallbackSampleCount,
                overlappingSampleCount = overlappingSampleCount,
                maximumOverlapSpread = maximumOverlapSpread,
            )
        } else {
            null
        }
        val depthStats = lastLipDepthSamplingStats
        check(
            activeRuntime.updateTrackingTestLip(
                vertices = nativeLipVertices,
                indices = lipTessellator.indices,
                temporalFlowEnabled = false,
                lipDepthBias = lipDepthBias,
                sampledDepthMinimum = depthStats?.minimumNdcDepth ?: 0f,
                sampledDepthMaximum = depthStats?.maximumNdcDepth ?: 0f,
                visualizeSampledDepth = faceDepthEnabled && visualizeLipDepth,
                visible = true,
            ),
        ) { "ARCore Vulkan lip upload failed" }
        lastValidLipTimestampNs = timestampNs
    }

    private fun uploadFaceAttachedLip(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        state: FullFaceRenderState,
        opacity: Float,
    ): Boolean {
        if (!ensureFaceLipResources(state)) return false
        val outer = state.region(FaceRegion.LIPS_OUTER) ?: return false
        val inner = state.region(FaceRegion.LIPS_INNER) ?: return false
        DynamicLipContourCoverage.write(
            outer = outer,
            inner = inner,
            opacity = opacity,
            destination = dynamicLipContour,
        )

        val display = state.displayLandmarks
        val projectedSurface = projectedSurfaceCoordinates(state)
        val requiredComponents = display.pointCount * NATIVE_VERTEX_COMPONENTS
        if (requiredComponents > nativeLipVertices.size) return false
        var destination = 0
        repeat(display.pointCount) { index ->
            val coordinate = index * FaceLandmarkSet.COMPONENT_COUNT
            val x = projectedSurface[coordinate]
            val y = projectedSurface[coordinate + 1]
            val depth = projectedSurface[coordinate + 2]
            nativeLipVertices[destination++] = x
            nativeLipVertices[destination++] = y
            nativeLipVertices[destination++] = x
            nativeLipVertices[destination++] = y
            nativeLipVertices[destination++] = faceLipCoverage[index] * opacity
            nativeLipVertices[destination++] = depth
        }
        val vertices = if (requiredComponents == nativeLipVertices.size) {
            nativeLipVertices
        } else {
            nativeLipVertices.copyOf(requiredComponents)
        }
        var minimumDepth = Float.POSITIVE_INFINITY
        var maximumDepth = Float.NEGATIVE_INFINITY
        faceLipIndices.forEach { packedIndex ->
            val depth = projectedSurface[
                packedIndex.toInt() * FaceLandmarkSet.COMPONENT_COUNT + 2
            ]
            minimumDepth = minOf(minimumDepth, depth)
            maximumDepth = maxOf(maximumDepth, depth)
        }
        lastLipDepthSamplingStats = LipDepthSamplingStats(
            minimumNdcDepth = minimumDepth,
            maximumNdcDepth = maximumDepth,
            triangleSampleCount = faceLipIndices.size / FaceSurfaceTopology.INDICES_PER_TRIANGLE,
            fallbackSampleCount = 0,
            overlappingSampleCount = 0,
            maximumOverlapSpread = 0f,
        )
        check(
            activeRuntime.updateTrackingTestLip(
                vertices = vertices,
                indices = faceLipIndices,
                temporalFlowEnabled = false,
                lipDepthBias = lipDepthBias,
                sampledDepthMinimum = minimumDepth,
                sampledDepthMaximum = maximumDepth,
                visualizeSampledDepth = visualizeLipDepth,
                dynamicContour = dynamicLipContour,
                visible = true,
            ),
        ) { "ARCore Vulkan face-attached lip upload failed" }
        return true
    }

    private fun uploadRetainedFaceAttachedLipOpacity(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        opacity: Float,
    ): Boolean {
        if (faceLipCoverage.isEmpty() || faceLipIndices.isEmpty() ||
            lastValidLipTimestampNs <= 0L
        ) {
            return false
        }
        faceLipCoverage.indices.forEach { index ->
            nativeLipVertices[index * NATIVE_VERTEX_COMPONENTS + 4] =
                faceLipCoverage[index] * opacity
        }
        dynamicLipContour[DynamicLipContourCoverage.OPACITY_INDEX] = opacity
        val requiredComponents = faceLipCoverage.size * NATIVE_VERTEX_COMPONENTS
        val vertices = if (requiredComponents == nativeLipVertices.size) {
            nativeLipVertices
        } else {
            nativeLipVertices.copyOf(requiredComponents)
        }
        val depthStats = lastLipDepthSamplingStats
        check(
            activeRuntime.updateTrackingTestLip(
                vertices = vertices,
                indices = faceLipIndices,
                temporalFlowEnabled = false,
                lipDepthBias = lipDepthBias,
                sampledDepthMinimum = depthStats?.minimumNdcDepth ?: 0f,
                sampledDepthMaximum = depthStats?.maximumNdcDepth ?: 0f,
                visualizeSampledDepth = visualizeLipDepth,
                dynamicContour = dynamicLipContour,
                visible = opacity > 0f,
            ),
        ) { "ARCore Vulkan retained face-attached lip opacity upload failed" }
        return true
    }

    private fun uploadFaceSurface(
        activeRuntime: NativeVulkanDiagnosticRuntime,
        state: FullFaceRenderState?,
        timestampNs: Long,
        visibility: TrackingVisibilityDecision,
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
            if (visibility.retainLastGeometry && lastValidFaceTimestampNs > 0L) return
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
        val projectedSurface = state?.let(::projectedSurfaceCoordinates) ?: return
        projectedSurface.copyInto(nativeFaceVertices)
        check(
            activeRuntime.updateFaceOccluder(
                vertices = nativeFaceVertices,
                indices = topology.packedCopy(),
                visualizeDepth = visualizeFaceDepth,
                visible = true,
            ),
        ) { "ARCore Vulkan face-occluder upload failed" }
        lastValidFaceTimestampNs = timestampNs
    }

    private fun projectedSurfaceCoordinates(state: FullFaceRenderState): FloatArray {
        deformedSurfaceCoordinates?.takeIf {
            deformedSurfaceTimestampNs == state.renderTimestampNs
        }?.let { return it }
        val outer = state.region(FaceRegion.LIPS_OUTER)
        val inner = state.region(FaceRegion.LIPS_INNER)
        val textureCoordinates = state.surfaceTextureCoordinates
        val coordinates = if (outer != null && inner != null &&
            textureCoordinates != null && ensureFaceLipResources(state) &&
            outer.pointCount == LipLandmarkTopology.outerContour.size &&
            inner.pointCount == LipLandmarkTopology.innerContour.size
        ) {
            FaceSurfaceCameraLipDeformer.deform(
                canonicalLandmarks = state.canonicalLandmarks,
                displayLandmarks = state.displayLandmarks,
                textureCoordinates = textureCoordinates,
                deformationSupport = faceLipCoverage,
                facePose = state.globalPose,
                cameraFrame = state.cameraFrame,
                outerGeometry = outer,
                innerGeometry = inner,
                outerIndices = LipLandmarkTopology.outerContour,
                innerIndices = LipLandmarkTopology.innerContour,
            )
        } else {
            state.displayLandmarks.packedCopy()
        }
        deformedSurfaceTimestampNs = state.renderTimestampNs
        deformedSurfaceCoordinates = coordinates
        return coordinates
    }

    private fun ensureFaceLipResources(state: FullFaceRenderState): Boolean {
        val topology = state.surfaceTopology ?: return false
        val textureCoordinates = state.surfaceTextureCoordinates ?: return false
        if (faceLipTopology != topology) {
            faceLipTopology = topology
            faceLipIndices = topology.packedCopy()
        }
        if (faceLipTextureCoordinates != textureCoordinates) {
            faceLipTextureCoordinates = textureCoordinates
            faceLipCoverage = FaceSurfaceLipCoverage.build(
                textureCoordinates = textureCoordinates,
                outerContour = LipLandmarkTopology.outerContour,
                innerContour = LipLandmarkTopology.innerContour,
            )
            Log.i(
                TAG,
                "Canonical-UV camera-space lip vertices=" +
                    "${faceLipCoverage.count { it > 0f }} " +
                    "faceTriangles=${faceLipIndices.size / 3}",
            )
        }
        return faceLipIndices.isNotEmpty() && faceLipCoverage.any { it > 0f }
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
        val angles = lastRenderState?.let(::cameraSpaceAnglesDegrees)
        val depthStats = lastLipDepthSamplingStats
        val status = String.format(
                Locale.US,
                "ARCore + Vulkan %.1f FPS · depth=%s\n" +
                    "same Frame: camera + ARCore pose + hybrid lips\n" +
                    "yaw=%s pitch=%s loss=%d total=%d max=%d/%dms\n" +
                    "visibility=%.2f retain=%s current=%s\n" +
                    "lipLocal=%.2f age=%s residual=%s\n" +
                    "lipDepth=%s tri=%d fallback=%d overlap=%d spread=%s bias=%.6f\n" +
                    "debugFace=%s debugLip=%s\n%s",
                fps,
                if (faceDepthEnabled) "3D" else "2D",
                angles?.first?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a",
                angles?.second?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a",
                trackingLossEpisodes.consecutiveLossFrames,
                trackingLossEpisodes.totalLossFrames,
                trackingLossEpisodes.maximumLossFrames,
                trackingLossEpisodes.maximumLossDurationNs / 1_000_000L,
                lastTrackingVisibilityDecision.opacity,
                lastTrackingVisibilityDecision.retainLastGeometry,
                lastTrackingVisibilityDecision.useCurrentGeometry,
                lastRenderState?.attachmentQuality?.localDeformationWeight ?: 0f,
                lastRenderState?.attachmentQuality?.localObservationAgeNs
                    ?.let { "${it / 1_000_000L}ms" } ?: "global",
                lastRenderState?.attachmentQuality?.affineFitResidualNormalized
                    ?.let { String.format(Locale.US, "%.4f", it) } ?: "n/a",
                depthStats?.let {
                    String.format(Locale.US, "%.5f..%.5f", it.minimumNdcDepth, it.maximumNdcDepth)
                } ?: "n/a",
                depthStats?.triangleSampleCount ?: 0,
                depthStats?.fallbackSampleCount ?: 0,
                depthStats?.overlappingSampleCount ?: 0,
                depthStats?.maximumOverlapSpread
                    ?.let { String.format(Locale.US, "%.6f", it) } ?: "n/a",
                lipDepthBias,
                visualizeFaceDepth,
                visualizeLipDepth,
                runtime?.diagnostic ?: "Vulkan unavailable",
        )
        onStatus(status)
        Log.i(
            TAG,
            "FF5 ts=$timestampNs depth=$faceDepthEnabled yaw=${angles?.first} " +
                "pitch=${angles?.second} loss=${trackingLossEpisodes.consecutiveLossFrames}/" +
                "${trackingLossEpisodes.totalLossFrames} maxLoss=" +
                "${trackingLossEpisodes.maximumLossFrames}/" +
                "${trackingLossEpisodes.maximumLossDurationNs}ns visibility=" +
                "${lastTrackingVisibilityDecision.opacity}/" +
                "${lastTrackingVisibilityDecision.retainLastGeometry}/" +
                "${lastTrackingVisibilityDecision.useCurrentGeometry} localWeight=" +
                "${lastRenderState?.attachmentQuality?.localDeformationWeight} ageNs=" +
                "${lastRenderState?.attachmentQuality?.localObservationAgeNs} residual=" +
                "${lastRenderState?.attachmentQuality?.affineFitResidualNormalized} " +
                "depthStats=$depthStats bias=$lipDepthBias",
        )
        statusWindowStartNs = timestampNs
        statusWindowFrames = 0
    }

    private fun cameraSpaceAnglesDegrees(state: FullFaceRenderState): Pair<Float, Float>? {
        val cameraFromWorld = state.cameraFrame.cameraFromWorld
        val faceToWorld = state.globalPose.faceToWorld
        val cameraFromFace = FloatArray(MATRIX_ELEMENT_COUNT)
        for (column in 0 until MATRIX_DIMENSION) {
            for (row in 0 until MATRIX_DIMENSION) {
                var value = 0f
                for (component in 0 until MATRIX_DIMENSION) {
                    value += cameraFromWorld[component * MATRIX_DIMENSION + row] *
                        faceToWorld[column * MATRIX_DIMENSION + component]
                }
                cameraFromFace[column * MATRIX_DIMENSION + row] = value
            }
        }
        val transform = CanonicalFaceTransform.fromColumnMajor(cameraFromFace) ?: return null
        return Pair(
            Math.toDegrees(transform.metricYawRadians.toDouble()).toFloat(),
            Math.toDegrees(transform.metricPitchRadians.toDouble()).toFloat(),
        )
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
        faceLipTopology = null
        faceLipIndices = EMPTY_SHORTS
        faceLipTextureCoordinates = null
        faceLipCoverage = EMPTY_FLOATS
        deformedSurfaceTimestampNs = 0L
        deformedSurfaceCoordinates = null
        lastRenderState = null
        lastLipDepthSamplingStats = null
        trackingLossEpisodes.reset()
        trackingVisibilityController.reset()
        lastTrackingVisibilityDecision = TrackingVisibilityDecision.HIDDEN
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
        const val LOCAL_OBSERVATION_AGE_FADE_OUT_NS = 100_000_000L
        const val TRACKING_LOSS_HOLD_NS = 100_000_000L
        const val MAXIMUM_RUNTIME_RECOVERY_ATTEMPTS = 1
        const val NATIVE_VERTEX_COMPONENTS = 6
        const val MATRIX_DIMENSION = 4
        const val MATRIX_ELEMENT_COUNT = MATRIX_DIMENSION * MATRIX_DIMENSION
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

internal data class LipDepthSamplingStats(
    val minimumNdcDepth: Float,
    val maximumNdcDepth: Float,
    val triangleSampleCount: Int,
    val fallbackSampleCount: Int,
    val overlappingSampleCount: Int,
    val maximumOverlapSpread: Float,
) {
    init {
        require(minimumNdcDepth.isFinite())
        require(maximumNdcDepth.isFinite() && maximumNdcDepth >= minimumNdcDepth)
        require(triangleSampleCount >= 0)
        require(fallbackSampleCount >= 0)
        require(overlappingSampleCount >= 0)
        require(maximumOverlapSpread.isFinite() && maximumOverlapSpread >= 0f)
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
