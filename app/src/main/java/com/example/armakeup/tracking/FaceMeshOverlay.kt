package com.example.armakeup.tracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.armakeup.R
import com.google.mediapipe.tasks.components.containers.Connection
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/** Diagnostic overlay only. Makeup materials will be rendered by the GPU compositor. */
class FaceMeshOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rose_300)
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
        alpha = 150
    }
    private var landmarks: LandmarkRenderFrame? = null
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var imageTransform = NormalizedImageTransform(
        rotationDegrees = 0,
        mirrorHorizontal = false,
    )

    fun setResult(
        landmarks: LandmarkRenderFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
    ) {
        this.landmarks = landmarks
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        imageTransform = NormalizedImageTransform(rotationDegrees, mirrorHorizontal)
        postInvalidateOnAnimation()
    }

    fun clear() {
        if (landmarks == null) return
        landmarks = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val landmarks = landmarks ?: return
        if (width == 0 || height == 0 || sourceWidth <= 0 || sourceHeight <= 0) return

        val renderTimestampMs = SystemClock.uptimeMillis()
        val predictionSeconds = landmarks.predictionSeconds(renderTimestampMs)
        val transform = FillCenterTransform.calculate(
            viewWidth = width,
            viewHeight = height,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
        DIAGNOSTIC_CONNECTIONS.forEach { connection ->
            val start = connection.start().takeIf { it in 0 until landmarks.size }
                ?: return@forEach
            val end = connection.end().takeIf { it in 0 until landmarks.size }
                ?: return@forEach
            drawConnection(canvas, landmarks, start, end, predictionSeconds, transform)
        }
        if (landmarks.shouldAnimate(renderTimestampMs)) postInvalidateOnAnimation()
    }

    private fun drawConnection(
        canvas: Canvas,
        landmarks: LandmarkRenderFrame,
        start: Int,
        end: Int,
        predictionSeconds: Float,
        transform: FillCenterTransform,
    ) {
        val startX = landmarks.x(start, predictionSeconds)
        val startY = landmarks.y(start, predictionSeconds)
        val endX = landmarks.x(end, predictionSeconds)
        val endY = landmarks.y(end, predictionSeconds)
        canvas.drawLine(
            transform.mapX(imageTransform.mapX(startX, startY), sourceWidth),
            transform.mapY(imageTransform.mapY(startX, startY), sourceHeight),
            transform.mapX(imageTransform.mapX(endX, endY), sourceWidth),
            transform.mapY(imageTransform.mapY(endX, endY), sourceHeight),
            linePaint,
        )
    }

    companion object {
        private val DIAGNOSTIC_CONNECTIONS: Set<Connection> = buildSet {
            addAll(FaceLandmarker.FACE_LANDMARKS_FACE_OVAL)
            addAll(FaceLandmarker.FACE_LANDMARKS_LIPS)
            addAll(FaceLandmarker.FACE_LANDMARKS_LEFT_EYE)
            addAll(FaceLandmarker.FACE_LANDMARKS_LEFT_EYE_BROW)
            addAll(FaceLandmarker.FACE_LANDMARKS_LEFT_IRIS)
            addAll(FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE)
            addAll(FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE_BROW)
            addAll(FaceLandmarker.FACE_LANDMARKS_RIGHT_IRIS)
        }
    }
}
