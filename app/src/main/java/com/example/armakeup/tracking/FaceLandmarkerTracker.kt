package com.example.armakeup.tracking

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.camera.core.ImageProxy
import com.example.armakeup.R
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class FaceLandmarkerTracker(
    context: Context,
    private val callbackExecutor: Executor,
    private val listener: Listener,
    private val telemetrySink: TrackingTelemetrySink? = null,
    private val cameraCaptureMetadataStore: CameraCaptureMetadataStore,
) : AutoCloseable {

    private val applicationContext = context.applicationContext
    private val frameBufferPool = ArrayDeque<RgbaFrameBuffer>(FRAME_BUFFER_POOL_CAPACITY)
    private val landmarkPredictor = LandmarkMotionPredictor()
    private val frameTimestampResolver = CameraFrameTimestampResolver()
    private val frameQualityAnalyzer = SparseFrameQualityAnalyzer()
    private val deviceStateMonitor = DeviceStateMonitor(context)
    private var faceLandmarker: FaceLandmarker? = null
    private var activeDelegate = InferenceDelegate.GPU
    private var inFlightFrame: PreparedFrame? = null
    private var inFlightImage: MPImage? = null
    private var pendingFrame: PreparedFrame? = null
    private var lastDeliveredLandmarks: LandmarkRenderFrame? = null
    private var lastResultDeliveryTimestampMs = 0L
    private var lastMeasurementCaptureTimestampMs = NO_TIMESTAMP
    private var smoothedMlFps = 0f
    private var closed = false

    fun initialize() {
        if (closed || faceLandmarker != null) return

        faceLandmarker = try {
            createLandmarker(Delegate.GPU).also {
                activeDelegate = InferenceDelegate.GPU
            }
        } catch (gpuError: RuntimeException) {
            try {
                createLandmarker(Delegate.CPU).also {
                    activeDelegate = InferenceDelegate.CPU
                }
            } catch (cpuError: RuntimeException) {
                listener.onTrackerError(
                    cpuError.message ?: gpuError.message ?: "MediaPipe initialization failed",
                )
                return
            }
        }

        listener.onTrackerReady(activeDelegate)
    }

    private fun createLandmarker(delegate: Delegate): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .setDelegate(delegate)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            // The 478 landmarks already contain the geometry required by the current effects.
            // The canonical-face matrix is enabled only for an explicit debug telemetry session.
            // It remains a shadow output and never drives the visible renderer at this stage.
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(telemetrySink != null)
            .setResultListener { result, inputImage ->
                val callbackTimestampMs = SystemClock.uptimeMillis()
                executeCallback(inputImage) {
                    handleResult(result, inputImage, callbackTimestampMs)
                }
            }
            .setErrorListener { error ->
                executeCallback { handleInferenceError(error) }
            }
            .build()

        return FaceLandmarker.createFromOptions(applicationContext, options)
    }

    /**
     * Keeps one frame in inference and continuously overwrites one pending slot with the
     * newest camera frame. When inference completes, the freshest pending frame is submitted
     * immediately instead of waiting for the next camera tick.
     */
    fun detect(imageProxy: ImageProxy) {
        if (closed || faceLandmarker == null) {
            imageProxy.close()
            return
        }

        val analysisStartTimestampMs = SystemClock.uptimeMillis()
        val sourceWidth = imageProxy.width
        val sourceHeight = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val sensorTimestampNs = imageProxy.imageInfo.timestamp
        val frameTimestampMs = frameTimestampResolver.resolve(
            cameraTimestampNs = sensorTimestampNs,
            nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
            nowUptimeMs = SystemClock.uptimeMillis(),
        )

        val reusablePending = pendingFrame?.takeIf {
            inFlightFrame != null &&
                it.frameBuffer.width == sourceWidth &&
                it.frameBuffer.height == sourceHeight
        }
        val frameBuffer = reusablePending?.frameBuffer
            ?: obtainFrameBuffer(sourceWidth, sourceHeight)

        val rgbaCopyStartedAtNs = SystemClock.elapsedRealtimeNanos()
        try {
            imageProxy.use { proxy ->
                copyRgbaPixels(proxy, frameBuffer.buffer)
            }
        } catch (error: RuntimeException) {
            if (reusablePending == null) releaseFrameBuffer(frameBuffer)
            listener.onTrackerError(error.message ?: "Frame copy failed")
            return
        }
        val rgbaCopyDurationMs = elapsedMillisecondsSince(rgbaCopyStartedAtNs)
        val imageSignalQuality: ImageSignalQuality
        val qualityAnalysisDurationMs: Float
        if (telemetrySink == null) {
            imageSignalQuality = ImageSignalQuality.UNKNOWN
            qualityAnalysisDurationMs = Float.NaN
        } else {
            val qualityAnalysisStartedAtNs = SystemClock.elapsedRealtimeNanos()
            imageSignalQuality = frameQualityAnalyzer.analyze(
                frameBuffer.buffer,
                sourceWidth,
                sourceHeight,
            )
            qualityAnalysisDurationMs = elapsedMillisecondsSince(qualityAnalysisStartedAtNs)
        }

        val frame = PreparedFrame(
            frameBuffer = frameBuffer,
            timestampMs = frameTimestampMs,
            sensorTimestampNs = sensorTimestampNs,
            rotationDegrees = rotationDegrees,
            imageSignalQuality = imageSignalQuality,
            analysisStartTimestampMs = analysisStartTimestampMs,
            rgbaCopyDurationMs = rgbaCopyDurationMs,
            qualityAnalysisDurationMs = qualityAnalysisDurationMs,
        )

        if (inFlightFrame == null) {
            submit(frame)
        } else {
            if (reusablePending == null) {
                pendingFrame?.let { releaseFrameBuffer(it.frameBuffer) }
            }
            pendingFrame = frame
        }
    }

    private fun submit(frame: PreparedFrame) {
        val landmarker = faceLandmarker
        if (closed || landmarker == null) {
            releaseFrameBuffer(frame.frameBuffer)
            return
        }

        val mpImage = ByteBufferImageBuilder(
            frame.frameBuffer.buffer,
            frame.frameBuffer.width,
            frame.frameBuffer.height,
            MPImage.IMAGE_FORMAT_RGBA,
        ).build()
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(frame.rotationDegrees)
            .build()
        inFlightFrame = frame
        inFlightImage = mpImage
        frame.submitTimestampMs = SystemClock.uptimeMillis()

        try {
            landmarker.detectAsync(mpImage, processingOptions, frame.timestampMs)
        } catch (error: RuntimeException) {
            inFlightFrame = null
            inFlightImage = null
            mpImage.close()
            releaseFrameBuffer(frame.frameBuffer)
            submitLatestPendingFrame()
            listener.onTrackerError(error.message ?: "Frame processing failed")
        }
    }

    private fun handleResult(
        result: FaceLandmarkerResult,
        inputImage: MPImage,
        callbackTimestampMs: Long,
    ) {
        val callbackHandlerStartTimestampMs = SystemClock.uptimeMillis()
        val frame = inFlightFrame
        closeInputImages(inputImage)
        inFlightFrame = null
        inFlightImage = null

        if (frame == null) {
            submitLatestPendingFrame()
            return
        }

        val rotated = frame.rotationDegrees % 180 != 0
        val inputWidth = if (rotated) frame.frameBuffer.height else frame.frameBuffer.width
        val inputHeight = if (rotated) frame.frameBuffer.width else frame.frameBuffer.height
        releaseFrameBuffer(frame.frameBuffer)
        submitLatestPendingFrame()

        val resultTimestampMs = SystemClock.uptimeMillis()
        val resultProcessingStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val measuredLandmarks = result.faceLandmarks().firstOrNull()
        val rawCoordinates = if (measuredLandmarks != null) {
            FloatArray(
                measuredLandmarks.size * LandmarkRenderFrame.COORDINATE_COUNT,
            ).also { coordinates ->
                measuredLandmarks.forEachIndexed { index, landmark ->
                    val coordinateIndex = index * LandmarkRenderFrame.COORDINATE_COUNT
                    coordinates[coordinateIndex] = landmark.x()
                    coordinates[coordinateIndex + 1] = landmark.y()
                    coordinates[coordinateIndex + 2] = landmark.z()
                }
            }
        } else {
            FloatArray(0)
        }
        val trackedLandmarks = if (rawCoordinates.isNotEmpty()) {
            landmarkPredictor.update(rawCoordinates, frame.timestampMs)
        } else {
            landmarkPredictor.predictWithoutMeasurement(resultTimestampMs)
        }
        val renderLandmarks = trackedLandmarks
            ?.deliveredAt(resultTimestampMs)
            ?.smoothCorrectionFrom(lastDeliveredLandmarks, resultTimestampMs)
        lastDeliveredLandmarks = renderLandmarks

        val filteredCoordinates = trackedLandmarks?.copyBasePositions() ?: FloatArray(0)
        val facialTransformationMatrix = result.facialTransformationMatrixes()
            .orElse(emptyList())
            .firstOrNull()
            ?.takeIf { matrix ->
                matrix.size == FACIAL_TRANSFORMATION_MATRIX_SIZE && matrix.all(Float::isFinite)
            }
            ?.copyOf()
            ?: FloatArray(0)
        val resultProcessingDurationMs = elapsedMillisecondsSince(resultProcessingStartedAtNs)
        val latencyMs = (resultTimestampMs - frame.timestampMs).coerceAtLeast(0L)
        telemetrySink?.let { sink ->
            val cameraMetadata = cameraCaptureMetadataStore.consume(frame.sensorTimestampNs)
            val captureIntervalMs = if (lastMeasurementCaptureTimestampMs == NO_TIMESTAMP) {
                -1L
            } else {
                (frame.timestampMs - lastMeasurementCaptureTimestampMs).takeIf { it > 0L } ?: -1L
            }
            lastMeasurementCaptureTimestampMs = frame.timestampMs
            sink.recordMeasurement(
                TrackingMeasurementSample(
                    captureTimestampMs = frame.timestampMs,
                    sensorTimestampNs = frame.sensorTimestampNs,
                    deliveryTimestampMs = resultTimestampMs,
                    latencyMs = latencyMs,
                    mlFps = updateMlFps(resultTimestampMs),
                    // FaceLandmarkerResult 1.0.0 does not expose one calibrated face confidence.
                    confidence = Float.NaN,
                    facePresent = measuredLandmarks != null,
                    predictedOnly = trackedLandmarks?.predictedOnly == true,
                    rawLandmarks = rawCoordinates,
                    filteredLandmarks = filteredCoordinates,
                    velocities = trackedLandmarks?.copyVelocities() ?: FloatArray(0),
                    rawGeometry = TrackingGeometryExtractor.extract(rawCoordinates),
                    filteredGeometry = TrackingGeometryExtractor.extract(filteredCoordinates),
                    captureIntervalMs = captureIntervalMs,
                    frameQuality = TrackingFrameQuality(
                        meanLuma = frame.imageSignalQuality.meanLuma,
                        lumaStandardDeviation = frame.imageSignalQuality.lumaStandardDeviation,
                        meanGradient = frame.imageSignalQuality.meanGradient,
                        exposureTimeNs = cameraMetadata.exposureTimeNs,
                        sensitivityIso = cameraMetadata.sensitivityIso,
                        frameDurationNs = cameraMetadata.frameDurationNs,
                        rollingShutterSkewNs = cameraMetadata.rollingShutterSkewNs,
                        aeState = cameraMetadata.aeState,
                    ),
                    poseFitQuality = if (rawCoordinates.isEmpty()) {
                        TrackingPoseFitQuality.UNKNOWN
                    } else {
                        landmarkPredictor.latestPoseFitQuality
                    },
                    deviceState = deviceStateMonitor.snapshot(resultTimestampMs),
                    pipelineTiming = TrackingPipelineTiming(
                        analysisStartTimestampMs = frame.analysisStartTimestampMs,
                        submitTimestampMs = frame.submitTimestampMs,
                        callbackTimestampMs = callbackTimestampMs,
                        callbackHandlerStartTimestampMs = callbackHandlerStartTimestampMs,
                        rgbaCopyDurationMs = frame.rgbaCopyDurationMs,
                        qualityAnalysisDurationMs = frame.qualityAnalysisDurationMs,
                        resultProcessingDurationMs = resultProcessingDurationMs,
                    ),
                    facialTransformationMatrix = facialTransformationMatrix,
                ),
            )
        }

        listener.onTrackingResult(
            TrackingResult(
                renderLandmarks = renderLandmarks,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                rotationDegrees = frame.rotationDegrees,
                mirrorHorizontal = true,
                sensorTimestampNs = frame.sensorTimestampNs,
                latencyMs = latencyMs,
                delegate = activeDelegate,
            ),
        )
    }

    private fun updateMlFps(deliveryTimestampMs: Long): Float {
        val previousTimestampMs = lastResultDeliveryTimestampMs
        lastResultDeliveryTimestampMs = deliveryTimestampMs
        if (previousTimestampMs <= 0L || deliveryTimestampMs <= previousTimestampMs) {
            return smoothedMlFps
        }
        val instantaneousFps = MILLIS_PER_SECOND / (deliveryTimestampMs - previousTimestampMs)
        smoothedMlFps = if (smoothedMlFps <= 0f) {
            instantaneousFps
        } else {
            smoothedMlFps + ML_FPS_SMOOTHING * (instantaneousFps - smoothedMlFps)
        }
        return smoothedMlFps
    }

    private fun handleInferenceError(error: RuntimeException) {
        val frame = inFlightFrame
        inFlightImage?.close()
        inFlightImage = null
        inFlightFrame = null
        frame?.let { releaseFrameBuffer(it.frameBuffer) }
        submitLatestPendingFrame()
        listener.onTrackerError(error.message ?: "MediaPipe inference failed")
    }

    private fun submitLatestPendingFrame() {
        val next = pendingFrame ?: return
        pendingFrame = null
        submit(next)
    }

    private fun closeInputImages(callbackImage: MPImage) {
        val submittedImage = inFlightImage
        callbackImage.close()
        if (submittedImage != null && submittedImage !== callbackImage) {
            submittedImage.close()
        }
    }

    private fun obtainFrameBuffer(width: Int, height: Int): RgbaFrameBuffer {
        val iterator = frameBufferPool.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.width == width && candidate.height == height) {
                iterator.remove()
                candidate.buffer.clear()
                return candidate
            }
        }
        return RgbaFrameBuffer(
            buffer = ByteBuffer.allocateDirect(width * height * RGBA_BYTES_PER_PIXEL),
            width = width,
            height = height,
        )
    }

    private fun releaseFrameBuffer(frameBuffer: RgbaFrameBuffer) {
        frameBuffer.buffer.clear()
        if (frameBufferPool.size < FRAME_BUFFER_POOL_CAPACITY) {
            frameBufferPool.addLast(frameBuffer)
        }
    }

    private fun copyRgbaPixels(imageProxy: ImageProxy, target: ByteBuffer) {
        val plane = imageProxy.planes.firstOrNull()
            ?: throw IllegalArgumentException("RGBA frame has no image plane")
        require(plane.pixelStride == RGBA_BYTES_PER_PIXEL) {
            "Unsupported RGBA pixel stride ${plane.pixelStride}"
        }

        val rowBytes = imageProxy.width * RGBA_BYTES_PER_PIXEL
        val requiredSourceBytes = (imageProxy.height - 1) * plane.rowStride + rowBytes
        val source = plane.buffer.duplicate().apply { clear() }
        require(requiredSourceBytes <= source.capacity()) {
            "RGBA plane is smaller than its declared row layout"
        }
        require(target.capacity() >= rowBytes * imageProxy.height) {
            "Reusable RGBA buffer is too small"
        }

        target.clear()
        if (plane.rowStride == rowBytes) {
            source.limit(rowBytes * imageProxy.height)
            target.put(source)
        } else {
            repeat(imageProxy.height) { row ->
                val rowStart = row * plane.rowStride
                source.clear()
                source.position(rowStart)
                source.limit(rowStart + rowBytes)
                target.put(source)
            }
        }
        target.flip()
    }

    private fun elapsedMillisecondsSince(startTimestampNs: Long): Float =
        (SystemClock.elapsedRealtimeNanos() - startTimestampNs)
            .coerceAtLeast(0L) / NANOSECONDS_PER_MILLISECOND

    private fun executeCallback(inputImage: MPImage? = null, block: () -> Unit) {
        try {
            callbackExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            inputImage?.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        faceLandmarker?.close()
        faceLandmarker = null
        inFlightImage?.close()
        inFlightImage = null
        inFlightFrame = null
        pendingFrame = null
        frameBufferPool.clear()
        landmarkPredictor.reset()
        lastDeliveredLandmarks = null
        lastResultDeliveryTimestampMs = 0L
        lastMeasurementCaptureTimestampMs = NO_TIMESTAMP
        smoothedMlFps = 0f
    }

    private data class RgbaFrameBuffer(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
    )

    private data class PreparedFrame(
        val frameBuffer: RgbaFrameBuffer,
        val timestampMs: Long,
        val sensorTimestampNs: Long,
        val rotationDegrees: Int,
        val imageSignalQuality: ImageSignalQuality,
        val analysisStartTimestampMs: Long,
        val rgbaCopyDurationMs: Float,
        val qualityAnalysisDurationMs: Float,
        var submitTimestampMs: Long = NO_TIMESTAMP,
    )

    data class TrackingResult(
        val renderLandmarks: LandmarkRenderFrame?,
        val inputWidth: Int,
        val inputHeight: Int,
        val rotationDegrees: Int,
        val mirrorHorizontal: Boolean,
        val sensorTimestampNs: Long,
        val latencyMs: Long,
        val delegate: InferenceDelegate,
    )

    enum class InferenceDelegate(@param:StringRes val labelRes: Int) {
        GPU(R.string.delegate_gpu),
        CPU(R.string.delegate_cpu),
    }

    interface Listener {
        fun onTrackerReady(delegate: InferenceDelegate)
        fun onTrackingResult(result: TrackingResult)
        fun onTrackerError(message: String)
    }

    companion object {
        private const val MODEL_ASSET_PATH = "face_landmarker.task"
        private const val MIN_CONFIDENCE = 0.5f
        private const val FRAME_BUFFER_POOL_CAPACITY = 2
        private const val RGBA_BYTES_PER_PIXEL = 4
        private const val MILLIS_PER_SECOND = 1_000f
        private const val ML_FPS_SMOOTHING = 0.2f
        private const val NANOSECONDS_PER_MILLISECOND = 1_000_000f
        private const val FACIAL_TRANSFORMATION_MATRIX_SIZE = 16
        private const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
