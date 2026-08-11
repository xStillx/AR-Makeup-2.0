package com.example.armakeup.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.example.armakeup.R
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class FaceLandmarkerTracker(
    context: Context,
    private val callbackExecutor: Executor,
    private val listener: Listener,
) : AutoCloseable {

    private val applicationContext = context.applicationContext
    private val bitmapPool = ArrayDeque<Bitmap>(BITMAP_POOL_CAPACITY)
    private val landmarkPredictor = LandmarkMotionPredictor()
    private val frameTimestampResolver = CameraFrameTimestampResolver()
    private var faceLandmarker: FaceLandmarker? = null
    private var activeDelegate = InferenceDelegate.GPU
    private var inFlightFrame: PreparedFrame? = null
    private var inFlightImage: MPImage? = null
    private var pendingFrame: PreparedFrame? = null
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
            // Optional blendshape and matrix outputs are enabled only by effects that consume them.
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .setResultListener { result, inputImage ->
                executeCallback(inputImage) { handleResult(result, inputImage) }
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

        val sourceWidth = imageProxy.width
        val sourceHeight = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val frameTimestampMs = frameTimestampResolver.resolve(
            cameraTimestampNs = imageProxy.imageInfo.timestamp,
            nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
            nowUptimeMs = SystemClock.uptimeMillis(),
        )

        val reusablePending = pendingFrame?.takeIf {
            inFlightFrame != null &&
                it.bitmap.width == sourceWidth &&
                it.bitmap.height == sourceHeight
        }
        val bitmap = reusablePending?.bitmap ?: obtainBitmap(sourceWidth, sourceHeight)

        try {
            imageProxy.use { proxy ->
                proxy.planes[0].buffer.rewind()
                bitmap.copyPixelsFromBuffer(proxy.planes[0].buffer)
            }
        } catch (error: RuntimeException) {
            if (reusablePending == null) releaseBitmap(bitmap)
            listener.onTrackerError(error.message ?: "Frame copy failed")
            return
        }

        val frame = PreparedFrame(
            bitmap = bitmap,
            timestampMs = frameTimestampMs,
            rotationDegrees = rotationDegrees,
        )

        if (inFlightFrame == null) {
            submit(frame)
        } else {
            if (reusablePending == null) {
                pendingFrame?.let { releaseBitmap(it.bitmap) }
            }
            pendingFrame = frame
        }
    }

    private fun submit(frame: PreparedFrame) {
        val landmarker = faceLandmarker
        if (closed || landmarker == null) {
            releaseBitmap(frame.bitmap)
            return
        }

        val mpImage = BitmapImageBuilder(frame.bitmap).build()
        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(frame.rotationDegrees)
            .build()
        inFlightFrame = frame
        inFlightImage = mpImage

        try {
            landmarker.detectAsync(mpImage, processingOptions, frame.timestampMs)
        } catch (error: RuntimeException) {
            inFlightFrame = null
            inFlightImage = null
            mpImage.close()
            releaseBitmap(frame.bitmap)
            submitLatestPendingFrame()
            listener.onTrackerError(error.message ?: "Frame processing failed")
        }
    }

    private fun handleResult(result: FaceLandmarkerResult, inputImage: MPImage) {
        val frame = inFlightFrame
        closeInputImages(inputImage)
        inFlightFrame = null
        inFlightImage = null

        if (frame == null) {
            submitLatestPendingFrame()
            return
        }

        val rotated = frame.rotationDegrees % 180 != 0
        val inputWidth = if (rotated) frame.bitmap.height else frame.bitmap.width
        val inputHeight = if (rotated) frame.bitmap.width else frame.bitmap.height
        releaseBitmap(frame.bitmap)
        submitLatestPendingFrame()

        val measuredLandmarks = result.faceLandmarks().firstOrNull()
        val renderLandmarks = if (measuredLandmarks != null) {
            val coordinates = FloatArray(
                measuredLandmarks.size * LandmarkRenderFrame.COORDINATE_COUNT,
            )
            measuredLandmarks.forEachIndexed { index, landmark ->
                val coordinateIndex = index * LandmarkRenderFrame.COORDINATE_COUNT
                coordinates[coordinateIndex] = landmark.x()
                coordinates[coordinateIndex + 1] = landmark.y()
                coordinates[coordinateIndex + 2] = landmark.z()
            }
            landmarkPredictor.update(coordinates, frame.timestampMs)
        } else {
            landmarkPredictor.predictWithoutMeasurement(SystemClock.uptimeMillis())
        }

        listener.onTrackingResult(
            TrackingResult(
                renderLandmarks = renderLandmarks,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                rotationDegrees = frame.rotationDegrees,
                mirrorHorizontal = true,
                latencyMs = (SystemClock.uptimeMillis() - frame.timestampMs).coerceAtLeast(0L),
                delegate = activeDelegate,
            ),
        )
    }

    private fun handleInferenceError(error: RuntimeException) {
        val frame = inFlightFrame
        inFlightImage?.close()
        inFlightImage = null
        inFlightFrame = null
        frame?.let { releaseBitmap(it.bitmap) }
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

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val iterator = bitmapPool.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.width == width && candidate.height == height && !candidate.isRecycled) {
                iterator.remove()
                return candidate
            }
        }
        return createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun releaseBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (bitmapPool.size < BITMAP_POOL_CAPACITY) {
            bitmapPool.addLast(bitmap)
        } else {
            bitmap.recycle()
        }
    }

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
        inFlightFrame?.bitmap?.recycle()
        inFlightFrame = null
        pendingFrame?.bitmap?.recycle()
        pendingFrame = null
        bitmapPool.forEach(Bitmap::recycle)
        bitmapPool.clear()
        landmarkPredictor.reset()
    }

    private data class PreparedFrame(
        val bitmap: Bitmap,
        val timestampMs: Long,
        val rotationDegrees: Int,
    )

    data class TrackingResult(
        val renderLandmarks: LandmarkRenderFrame?,
        val inputWidth: Int,
        val inputHeight: Int,
        val rotationDegrees: Int,
        val mirrorHorizontal: Boolean,
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
        private const val BITMAP_POOL_CAPACITY = 2
    }
}
