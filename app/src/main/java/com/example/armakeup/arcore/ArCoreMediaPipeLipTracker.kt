package com.example.armakeup.arcore

import android.os.SystemClock
import android.util.Log
import com.example.armakeup.tracking.FaceObservation
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
    private val onError: (String) -> Unit,
) : AutoCloseable {

    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val busy = AtomicBoolean(false)
    private val latestObservation = AtomicReference<Observation?>()
    private val latestCompletion = AtomicReference<Completion?>()
    private val sensorTimestampGate = MonotonicSensorTimestampGate()
    private val conversionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arcore-mediapipe-conversion")
    }
    private var landmarker: FaceLandmarker? = null
    private var activeCameraImage: android.media.Image? = null
    private var activeInput: MPImage? = null
    private var activeSensorTimestampNs = 0L
    private var activeSubmittedAtNs = 0L
    private var activeConversionDurationMs = 0f
    private var activeInputWidth = 0
    private var activeInputHeight = 0
    private var activeRotationDegrees = 0
    private var reusableInferenceBuffer: ByteBuffer? = null
    private var inferenceWidth = 0
    private var inferenceHeight = 0
    private var lastSubmittedTimestampMs = Long.MIN_VALUE
    private var lastResultTimestampNs = 0L
    private var smoothedFps = 0f
    @Volatile
    private var closed = false

    fun initialize() {
        synchronized(lock) {
            if (closed || landmarker != null) return
            landmarker = try {
                createLandmarker(Delegate.GPU)
            } catch (gpuError: RuntimeException) {
                Log.w(TAG, "MediaPipe GPU initialization failed, using CPU", gpuError)
                createLandmarker(Delegate.CPU)
            }
        }
    }

    fun tryDetect(frame: Frame, rotationDegrees: Int, requireSameTimestamp: Boolean = false): Long? {
        val activeLandmarker = synchronized(lock) {
            if (closed) return null
            landmarker
        } ?: return null
        if (!busy.compareAndSet(false, true)) return null

        val cameraImage = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            busy.set(false)
            return null
        } catch (error: RuntimeException) {
            busy.set(false)
            onError("ARCore camera image acquisition failed: ${error.message}")
            return null
        }
        if ((requireSameTimestamp && cameraImage.timestamp != frame.timestamp) ||
            !sensorTimestampGate.accept(cameraImage.timestamp)
        ) {
            cameraImage.close()
            busy.set(false)
            return null
        }
        val sensorTimestampNs = cameraImage.timestamp
        synchronized(lock) { activeCameraImage = cameraImage }
        return try {
            conversionExecutor.execute {
                prepareAndSubmit(
                    landmarker = activeLandmarker,
                    cameraImage = cameraImage,
                    rotationDegrees = rotationDegrees,
                )
            }
            sensorTimestampNs
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { if (activeCameraImage === cameraImage) activeCameraImage = null }
            cameraImage.close()
            busy.set(false)
            null
        }
    }

    /** Null means pending; a completion with null observation means a finished no-face/error frame. */
    fun completionFor(timestampNs: Long): Completion? = latestCompletion.get()?.takeIf {
        it.sensorTimestampNs == timestampNs
    }

    private fun prepareAndSubmit(
        landmarker: FaceLandmarker,
        cameraImage: android.media.Image,
        rotationDegrees: Int,
    ) {
        val sensorTimestampNs = cameraImage.timestamp
        var timestampMs = sensorTimestampNs / NANOS_PER_MILLISECOND
        val conversionStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val inferenceBuffer = try {
            obtainInferenceBuffer(cameraImage.width, cameraImage.height).also { target ->
                convertYuv420ToRgba(cameraImage, target)
            }
        } catch (error: RuntimeException) {
            releaseCameraImage(cameraImage)
            busy.set(false)
            publishCompletion(sensorTimestampNs, null)
            onError("ARCore YUV conversion failed: ${error.message}")
            return
        }
        val conversionDurationMs = (SystemClock.elapsedRealtimeNanos() - conversionStartedAtNs) /
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
            publishCompletion(sensorTimestampNs, null)
            onError("MediaPipe RGBA wrapping failed: ${error.message}")
            return
        }

        val rotated = rotationDegrees % HALF_ROTATION != 0
        val inputWidth = if (rotated) inferenceHeight else inferenceWidth
        val inputHeight = if (rotated) inferenceWidth else inferenceHeight

        synchronized(lock) {
            if (closed) {
                inputImage.close()
                busy.set(false)
                publishCompletion(sensorTimestampNs, null)
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
            activeInputWidth = inputWidth
            activeInputHeight = inputHeight
            activeRotationDegrees = rotationDegrees
        }
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotationDegrees)
            .build()
        try {
            landmarker.detectAsync(inputImage, processingOptions, timestampMs)
        } catch (error: RuntimeException) {
            releaseActiveInput(inputImage)
            publishCompletion(sensorTimestampNs, null)
            onError("MediaPipe ARCore frame submission failed: ${error.message}")
        }
    }

    fun latest(): Observation? = latestObservation.get()

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
        latestCompletion.set(null)
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
                val (input, sensorTimestampNs) = synchronized(lock) {
                    Pair(activeInput, activeSensorTimestampNs)
                }
                if (input != null) releaseActiveInput(input)
                publishCompletion(sensorTimestampNs, null)
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
        val inputWidth: Int
        val inputHeight: Int
        val rotationDegrees: Int
        synchronized(lock) {
            sensorTimestampNs = activeSensorTimestampNs
            submittedAtNs = activeSubmittedAtNs
            conversionDurationMs = activeConversionDurationMs
            inputWidth = activeInputWidth
            inputHeight = activeInputHeight
            rotationDegrees = activeRotationDegrees
        }
        val landmarks = result.faceLandmarks().firstOrNull()
        var observation: Observation? = null
        if (landmarks != null) {
            val coordinates = FloatArray(landmarks.size * COMPONENT_COUNT)
            landmarks.forEachIndexed { index, landmark ->
                val output = index * COMPONENT_COUNT
                coordinates[output] = landmark.x()
                coordinates[output + 1] = landmark.y()
                coordinates[output + 2] = landmark.z()
            }
            val faceObservation = MediaPipeFaceObservationAdapter.create(
                coordinates = coordinates,
                sensorTimestampNs = sensorTimestampNs,
                sourceWidth = inputWidth,
                sourceHeight = inputHeight,
                rotationDegrees = rotationDegrees,
            )
            if (faceObservation == null) {
                releaseActiveInput(inputImage)
                publishCompletion(sensorTimestampNs, null)
                return
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
            observation = Observation(
                    face = faceObservation,
                    coordinates = coordinates,
                    sensorTimestampNs = sensorTimestampNs,
                    inferenceDurationMs = (resultAtNs - submittedAtNs).coerceAtLeast(0L) /
                        NANOS_PER_MILLISECOND.toFloat(),
                    conversionDurationMs = conversionDurationMs,
                    smoothedFps = smoothedFps,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    rotationDegrees = rotationDegrees,
                )
            latestObservation.set(observation)
        }
        releaseActiveInput(inputImage)
        // Publish a fully released result. The GL thread polls this without waiting for inference.
        publishCompletion(sensorTimestampNs, observation)
    }

    private fun publishCompletion(sensorTimestampNs: Long, observation: Observation?) {
        if (sensorTimestampNs <= 0L) return
        if (closed) return
        latestCompletion.updateAndGet { current ->
            if (current == null || sensorTimestampNs >= current.sensorTimestampNs) {
                Completion(sensorTimestampNs, observation)
            } else current
        }
    }

    private fun releaseActiveInput(inputImage: MPImage) {
        synchronized(lock) {
            if (activeInput === inputImage) activeInput = null
            activeSensorTimestampNs = 0L
            activeSubmittedAtNs = 0L
            activeConversionDurationMs = 0f
            activeInputWidth = 0
            activeInputHeight = 0
            activeRotationDegrees = 0
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

    private fun convertYuv420ToRgba(image: android.media.Image, target: ByteBuffer) {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, received ${image.format}"
        }
        require(image.planes.size >= 3) { "YUV image is missing chroma planes" }
        if (!NativeYuv420Converter.convert(image, target)) {
            error("Native YUV_420_888 to RGBA conversion failed")
        }
        target.position(0)
    }

    data class Observation(
        val face: FaceObservation,
        val coordinates: FloatArray,
        val sensorTimestampNs: Long,
        val inferenceDurationMs: Float,
        val conversionDurationMs: Float,
        val smoothedFps: Float,
        val inputWidth: Int,
        val inputHeight: Int,
        val rotationDegrees: Int,
    )

    data class Completion(
        val sensorTimestampNs: Long,
        val observation: Observation?,
    )

    private companion object {
        const val TAG = "ARMakeupArCore"
        const val MODEL_ASSET_PATH = "face_landmarker.task"
        const val MIN_CONFIDENCE = 0.5f
        const val COMPONENT_COUNT = 3
        const val RGBA_BYTES_PER_PIXEL = 4
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val FPS_RESPONSE = 0.2f
        const val HALF_ROTATION = 180
    }
}
