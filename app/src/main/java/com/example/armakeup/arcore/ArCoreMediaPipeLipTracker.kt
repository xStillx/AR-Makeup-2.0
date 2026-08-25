package com.example.armakeup.arcore

import android.os.SystemClock
import android.util.Log
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.render.FaceAnchoredLipContourStabilizer
import com.example.armakeup.tracking.TrackingGeometryExtractor
import com.example.armakeup.tracking.face.FaceCoordinateSpace
import com.example.armakeup.tracking.face.FaceFeatureObservationState
import com.example.armakeup.tracking.face.FaceLandmarkSet
import com.example.armakeup.tracking.face.FaceObservation
import com.example.armakeup.tracking.face.FaceObservationDiagnostics
import com.example.armakeup.tracking.face.FaceObservationFeatures
import com.example.armakeup.tracking.face.FaceObservationQuality
import com.example.armakeup.tracking.face.FaceObservationRole
import com.example.armakeup.tracking.face.FaceTopologies
import com.example.armakeup.tracking.face.FaceTrackingBackend
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ARCore-camera MediaPipe shadow backend.
 *
 * Only one camera image may be in flight. Busy frames are skipped before acquiring an Image, so
 * ARCore never accumulates an ML queue and always remains the owner of the visible camera cadence.
 */
internal class ArCoreMediaPipeLipTracker(
    context: android.content.Context,
    private val forceCpuDelegate: Boolean = false,
    private val onError: (String) -> Unit,
) : FaceTrackingBackend {

    override val backendId: String = BACKEND_ID
    override val role: FaceObservationRole = FaceObservationRole.LOCAL_DEFORMATION
    override val topology = FaceTopologies.MEDIAPIPE_FACE_LANDMARKER_478

    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val busy = AtomicBoolean(false)
    private val latestObservation = AtomicReference<FaceObservation?>()
    private val latestRawObservation = AtomicReference<FaceObservation?>()
    private val lipContourMotionDiagnostics = LipContourMotionDiagnostics()
    private val localLipContourStabilizer = FaceAnchoredLipContourStabilizer(
        localCutoffHz = LOCAL_CONTOUR_CUTOFF_HZ,
        motionCutoffHz = LOCAL_CONTOUR_MOTION_CUTOFF_HZ,
        maximumFrameGapMs = LOCAL_CONTOUR_MAXIMUM_GAP_MS,
    )
    private val stableAnchorCoordinates =
        FloatArray(TrackingGeometryExtractor.stableAnchorIndices.size * XY_COMPONENT_COUNT)
    private val rawOuterLipCoordinates =
        FloatArray(LipLandmarkTopology.outerContour.size * XY_COMPONENT_COUNT)
    private val rawInnerLipCoordinates =
        FloatArray(LipLandmarkTopology.innerContour.size * XY_COMPONENT_COUNT)
    private val outerLipCoordinates =
        FloatArray(LipLandmarkTopology.outerContour.size * XY_COMPONENT_COUNT)
    private val innerLipCoordinates =
        FloatArray(LipLandmarkTopology.innerContour.size * XY_COMPONENT_COUNT)
    private val conversionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arcore-mediapipe-conversion")
    }
    private var landmarker: FaceLandmarker? = null
    private var activeDelegate = Delegate.GPU
    private var activeCameraImage: android.media.Image? = null
    private var activeInput: MPImage? = null
    private var activeSensorTimestampNs = 0L
    private var activeSubmittedAtNs = 0L
    private var activeConversionDurationMs = 0f
    private var reusableInferenceBuffer: ByteBuffer? = null
    private var inferenceWidth = 0
    private var inferenceHeight = 0
    private var lastSubmittedTimestampMs = Long.MIN_VALUE
    private var lastResultTimestampNs = 0L
    private var smoothedFps = 0f
    private var lastTimingLogAtNs = 0L
    private var closed = false

    fun initialize() {
        synchronized(lock) {
            if (closed || landmarker != null) return
            landmarker = if (forceCpuDelegate) {
                createLandmarker(Delegate.CPU).also {
                    activeDelegate = Delegate.CPU
                }
            } else {
                try {
                    createLandmarker(Delegate.GPU).also {
                        activeDelegate = Delegate.GPU
                    }
                } catch (gpuError: RuntimeException) {
                    Log.w(TAG, "MediaPipe GPU initialization failed, using CPU", gpuError)
                    createLandmarker(Delegate.CPU).also {
                        activeDelegate = Delegate.CPU
                    }
                }
            }
            Log.i(TAG, "FF5 MediaPipe delegate=${activeDelegate.name}")
        }
    }

    fun tryDetect(frame: Frame, rotationDegrees: Int) {
        val activeLandmarker = synchronized(lock) {
            if (closed) return
            landmarker
        } ?: return
        if (!busy.compareAndSet(false, true)) return

        val cameraImage = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            busy.set(false)
            return
        } catch (error: RuntimeException) {
            busy.set(false)
            onError("ARCore camera image acquisition failed: ${error.message}")
            return
        }
        synchronized(lock) { activeCameraImage = cameraImage }
        try {
            conversionExecutor.execute {
                prepareAndSubmit(
                    landmarker = activeLandmarker,
                    cameraImage = cameraImage,
                    rotationDegrees = rotationDegrees,
                )
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { if (activeCameraImage === cameraImage) activeCameraImage = null }
            cameraImage.close()
            busy.set(false)
        }
    }

    private fun prepareAndSubmit(
        landmarker: FaceLandmarker,
        cameraImage: android.media.Image,
        rotationDegrees: Int,
    ) {
        val sensorTimestampNs = cameraImage.timestamp
        var timestampMs = sensorTimestampNs / NANOS_PER_MILLISECOND
        val inferenceSize = MediaPipeInferenceSizePolicy.fitWithin(
            sourceWidth = cameraImage.width,
            sourceHeight = cameraImage.height,
            maximumLongEdge = MAXIMUM_INFERENCE_LONG_EDGE,
        )
        val conversionStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val inferenceBuffer = try {
            obtainInferenceBuffer(inferenceSize.width, inferenceSize.height).also { target ->
                convertYuv420ToRgba(
                    image = cameraImage,
                    target = target,
                    outputWidth = inferenceSize.width,
                    outputHeight = inferenceSize.height,
                )
            }
        } catch (error: RuntimeException) {
            releaseCameraImage(cameraImage)
            busy.set(false)
            onError("ARCore YUV conversion failed: ${error.message}")
            return
        }
        val conversionDurationMs =
            (SystemClock.elapsedRealtimeNanos() - conversionStartedAtNs) /
                NANOS_PER_MILLISECOND.toFloat()
        releaseCameraImage(cameraImage)

        val inputImage = try {
            ByteBufferImageBuilder(
                inferenceBuffer,
                inferenceWidth,
                inferenceHeight,
                MPImage.IMAGE_FORMAT_RGBA,
            ).build()
        } catch (error: RuntimeException) {
            busy.set(false)
            onError("MediaPipe RGBA wrapping failed: ${error.message}")
            return
        }

        synchronized(lock) {
            if (closed) {
                inputImage.close()
                busy.set(false)
                return
            }
            if (timestampMs <= lastSubmittedTimestampMs) {
                timestampMs = lastSubmittedTimestampMs + 1L
            }
            lastSubmittedTimestampMs = timestampMs
            activeInput = inputImage
            activeSensorTimestampNs = sensorTimestampNs
            activeSubmittedAtNs = SystemClock.elapsedRealtimeNanos()
            activeConversionDurationMs = conversionDurationMs
        }
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        try {
            landmarker.detectAsync(inputImage, processingOptions, timestampMs)
        } catch (error: RuntimeException) {
            releaseActiveInput(inputImage)
            onError("MediaPipe ARCore frame submission failed: ${error.message}")
        }
    }

    override fun latestObservation(): FaceObservation? = latestObservation.get()

    fun latestRawObservation(): FaceObservation? = latestRawObservation.get()

    override fun close() {
        val inputToClose: MPImage?
        val cameraImageToClose: android.media.Image?
        synchronized(lock) {
            if (closed) return
            closed = true
            inputToClose = activeInput
            activeInput = null
            cameraImageToClose = activeCameraImage
            activeCameraImage = null
            landmarker?.close()
            landmarker = null
        }
        conversionExecutor.shutdownNow()
        cameraImageToClose?.close()
        inputToClose?.close()
        busy.set(false)
        latestObservation.set(null)
        latestRawObservation.set(null)
        localLipContourStabilizer.reset()
        lipContourMotionDiagnostics.reset()
    }

    private fun createLandmarker(delegate: Delegate): FaceLandmarker {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .setDelegate(delegate)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .setResultListener(::handleResult)
            .setErrorListener { error ->
                val input = synchronized(lock) { activeInput }
                if (input != null) releaseActiveInput(input)
                onError("MediaPipe ARCore inference failed: ${error.message}")
            }
            .build()
        return FaceLandmarker.createFromOptions(applicationContext, options)
    }

    private fun handleResult(result: FaceLandmarkerResult, inputImage: MPImage) {
        val resultAtNs = SystemClock.elapsedRealtimeNanos()
        val sensorTimestampNs: Long
        val submittedAtNs: Long
        val conversionDurationMs: Float
        synchronized(lock) {
            sensorTimestampNs = activeSensorTimestampNs
            submittedAtNs = activeSubmittedAtNs
            conversionDurationMs = activeConversionDurationMs
        }
        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks != null) {
            val coordinates = FloatArray(landmarks.size * COMPONENT_COUNT)
            landmarks.forEachIndexed { index, landmark ->
                val output = index * COMPONENT_COUNT
                coordinates[output] = landmark.x()
                coordinates[output + 1] = landmark.y()
                coordinates[output + 2] = landmark.z()
            }
            val intervalNs = resultAtNs - lastResultTimestampNs
            val instantaneousFps = if (lastResultTimestampNs > 0L && intervalNs > 0L) {
                NANOS_PER_SECOND / intervalNs.toFloat()
            } else {
                0f
            }
            smoothedFps = if (smoothedFps == 0f) {
                instantaneousFps
            } else {
                smoothedFps + FPS_RESPONSE * (instantaneousFps - smoothedFps)
            }
            lastResultTimestampNs = resultAtNs
            if (resultAtNs - lastTimingLogAtNs >= NANOS_PER_SECOND) {
                val inferenceDurationMs =
                    (resultAtNs - submittedAtNs).coerceAtLeast(0L) /
                        NANOS_PER_MILLISECOND.toFloat()
                val observationAgeMs =
                    (resultAtNs - sensorTimestampNs).coerceAtLeast(0L) /
                        NANOS_PER_MILLISECOND.toFloat()
                Log.i(
                    TAG,
                    "FF5 mediaPipeTiming delegate=${activeDelegate.name} " +
                        "input=${inferenceWidth}x$inferenceHeight " +
                        "fps=$smoothedFps yuvMs=$conversionDurationMs " +
                        "inferenceMs=$inferenceDurationMs resultAgeMs=$observationAgeMs",
                )
                lastTimingLogAtNs = resultAtNs
            }
            if (landmarks.size == topology.pointCount) {
                val rawCoordinates = coordinates.copyOf()
                stabilizeLocalLipShape(coordinates, sensorTimestampNs)
                val stabilizedObservation =
                    FaceObservation(
                        backendId = backendId,
                        role = role,
                        sensorTimestampNs = sensorTimestampNs,
                        topology = topology,
                        imageLandmarks = FaceLandmarkSet.takeOwnership(
                            topology = topology,
                            coordinateSpace = FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
                            packedCoordinates = coordinates,
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
                        diagnostics = FaceObservationDiagnostics(
                            resultTimestampNs = resultAtNs,
                            inferenceDurationMs = (resultAtNs - submittedAtNs).coerceAtLeast(0L) /
                                NANOS_PER_MILLISECOND.toFloat(),
                            conversionDurationMs = conversionDurationMs,
                            smoothedFps = smoothedFps,
                        ),
                    )
                latestRawObservation.set(
                    stabilizedObservation.copy(
                        imageLandmarks = FaceLandmarkSet.takeOwnership(
                            topology = topology,
                            coordinateSpace = FaceCoordinateSpace.NORMALIZED_IMAGE_TOP_LEFT,
                            packedCoordinates = rawCoordinates,
                        ),
                    ),
                )
                latestObservation.set(stabilizedObservation)
            }
        }
        releaseActiveInput(inputImage)
    }

    /**
     * Removes only high-frequency MediaPipe lip-shape noise before hybrid composition.
     *
     * The stabilizer transports its previous contour through the current MediaPipe rigid
     * eye/nose/cheek pose first. Hybrid composition then maps that local shape into the current
     * ARCore pose, so neither camera motion nor head motion receives an additional low-pass lag.
     */
    private fun stabilizeLocalLipShape(coordinates: FloatArray, sensorTimestampNs: Long) {
        copyRegionCoordinates(
            source = coordinates,
            landmarkIndices = TrackingGeometryExtractor.stableAnchorIndices,
            destination = stableAnchorCoordinates,
        )
        copyRegionCoordinates(
            source = coordinates,
            landmarkIndices = LipLandmarkTopology.outerContour,
            destination = outerLipCoordinates,
        )
        copyRegionCoordinates(
            source = coordinates,
            landmarkIndices = LipLandmarkTopology.innerContour,
            destination = innerLipCoordinates,
        )
        outerLipCoordinates.copyInto(rawOuterLipCoordinates)
        innerLipCoordinates.copyInto(rawInnerLipCoordinates)
        localLipContourStabilizer.stabilize(
            anchors = stableAnchorCoordinates,
            outerContour = outerLipCoordinates,
            innerContour = innerLipCoordinates,
            timestampMs = sensorTimestampNs / NANOS_PER_MILLISECOND,
        )
        lipContourMotionDiagnostics.record(
            anchors = stableAnchorCoordinates,
            rawOuter = rawOuterLipCoordinates,
            rawInner = rawInnerLipCoordinates,
            stabilizedOuter = outerLipCoordinates,
            stabilizedInner = innerLipCoordinates,
            timestampMs = sensorTimestampNs / NANOS_PER_MILLISECOND,
        )
        writeRegionCoordinates(
            destination = coordinates,
            landmarkIndices = LipLandmarkTopology.outerContour,
            source = outerLipCoordinates,
        )
        writeRegionCoordinates(
            destination = coordinates,
            landmarkIndices = LipLandmarkTopology.innerContour,
            source = innerLipCoordinates,
        )
    }

    private fun copyRegionCoordinates(
        source: FloatArray,
        landmarkIndices: IntArray,
        destination: FloatArray,
    ) {
        landmarkIndices.forEachIndexed { pointIndex, landmarkIndex ->
            val sourceIndex = landmarkIndex * COMPONENT_COUNT
            val destinationIndex = pointIndex * XY_COMPONENT_COUNT
            destination[destinationIndex] = source[sourceIndex]
            destination[destinationIndex + 1] = source[sourceIndex + 1]
        }
    }

    private fun writeRegionCoordinates(
        destination: FloatArray,
        landmarkIndices: IntArray,
        source: FloatArray,
    ) {
        landmarkIndices.forEachIndexed { pointIndex, landmarkIndex ->
            val destinationIndex = landmarkIndex * COMPONENT_COUNT
            val sourceIndex = pointIndex * XY_COMPONENT_COUNT
            destination[destinationIndex] = source[sourceIndex]
            destination[destinationIndex + 1] = source[sourceIndex + 1]
        }
    }

    private fun releaseActiveInput(inputImage: MPImage) {
        synchronized(lock) {
            if (activeInput === inputImage) activeInput = null
            activeSensorTimestampNs = 0L
            activeSubmittedAtNs = 0L
            activeConversionDurationMs = 0f
        }
        inputImage.close()
        busy.set(false)
    }

    private fun releaseCameraImage(image: android.media.Image) {
        synchronized(lock) { if (activeCameraImage === image) activeCameraImage = null }
        image.close()
    }

    private fun obtainInferenceBuffer(width: Int, height: Int): ByteBuffer {
        val requiredBytes = width * height * RGBA_BYTES_PER_PIXEL
        val reusable = reusableInferenceBuffer
        val target = if (reusable == null || reusable.capacity() < requiredBytes) {
            ByteBuffer.allocateDirect(requiredBytes).also { reusableInferenceBuffer = it }
        } else {
            reusable
        }
        inferenceWidth = width
        inferenceHeight = height
        target.clear()
        target.limit(requiredBytes)
        return target
    }

    private fun convertYuv420ToRgba(
        image: android.media.Image,
        target: ByteBuffer,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, received ${image.format}"
        }
        require(image.planes.size >= 3) { "YUV image is missing chroma planes" }
        if (!NativeYuv420Converter.convert(image, target, outputWidth, outputHeight)) {
            error("Native YUV_420_888 to RGBA conversion failed")
        }
        target.position(0)
    }

    private companion object {
        const val TAG = "ARMakeupArCore"
        const val BACKEND_ID = "mediapipe-face-landmarker"
        const val MODEL_ASSET_PATH = "face_landmarker.task"
        const val MIN_CONFIDENCE = 0.5f
        const val COMPONENT_COUNT = 3
        const val RGBA_BYTES_PER_PIXEL = 4
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val FPS_RESPONSE = 0.2f
        const val XY_COMPONENT_COUNT = 2
        const val LOCAL_CONTOUR_CUTOFF_HZ = 8f
        const val LOCAL_CONTOUR_MOTION_CUTOFF_HZ = 30f
        const val MAXIMUM_INFERENCE_LONG_EDGE = 480
        const val LOCAL_CONTOUR_MAXIMUM_GAP_MS = 220L
    }
}
