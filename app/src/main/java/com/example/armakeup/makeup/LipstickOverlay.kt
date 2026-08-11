package com.example.armakeup.makeup

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.armakeup.R
import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.LandmarkRenderFrame
import com.example.armakeup.tracking.NormalizedImageTransform

/**
 * Hardware-accelerated matte lipstick prototype.
 *
 * COLOR blending replaces hue/saturation while retaining the camera luminance at every pixel,
 * so natural lip folds and shading remain visible. A subtle MULTIPLY pass compresses highlights
 * for a matte finish. The inner lip loop is a real hole and never colors the mouth or teeth.
 */
class LipstickOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lipstick_matte_red)
        style = Paint.Style.FILL
        configureColorBlend()
    }
    private val mattePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lipstick_matte_shadow)
        style = Paint.Style.FILL
        configureMatteBlend()
    }

    private val edgePath = Path()
    private val midPath = Path()
    private val corePath = Path()
    private val outerPoints = FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val innerPoints = FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)
    private val adjustedPoints = FloatArray(outerPoints.size)

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
        if (landmarks.size <= MAX_REQUIRED_LANDMARK_INDEX) return

        val renderTimestampMs = SystemClock.uptimeMillis()
        val predictionSeconds = landmarks.predictionSeconds(renderTimestampMs)
        val fillTransform = FillCenterTransform.calculate(
            viewWidth = width,
            viewHeight = height,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
        updateBaseContours(landmarks, predictionSeconds, fillTransform)
        buildLipPath(edgePath, insetFraction = 0f)
        buildLipPath(midPath, insetFraction = MID_INSET_FRACTION)
        buildLipPath(corePath, insetFraction = CORE_INSET_FRACTION)

        drawCoverage(canvas, edgePath, EDGE_COVERAGE)
        drawCoverage(canvas, midPath, MID_COVERAGE)
        drawCoverage(canvas, corePath, CORE_COVERAGE)

        mattePaint.alpha = MATTE_COVERAGE
        canvas.drawPath(corePath, mattePaint)

        if (landmarks.shouldAnimate(renderTimestampMs)) postInvalidateOnAnimation()
    }

    private fun updateBaseContours(
        landmarks: LandmarkRenderFrame,
        predictionSeconds: Float,
        fillTransform: FillCenterTransform,
    ) {
        LipLandmarkTopology.outerContour.forEachIndexed { pointIndex, landmarkIndex ->
            mapLandmark(
                landmarks,
                landmarkIndex,
                predictionSeconds,
                fillTransform,
                outerPoints,
                pointIndex,
            )
        }
        LipLandmarkTopology.innerContour.forEachIndexed { pointIndex, landmarkIndex ->
            mapLandmark(
                landmarks,
                landmarkIndex,
                predictionSeconds,
                fillTransform,
                innerPoints,
                pointIndex,
            )
        }
    }

    private fun mapLandmark(
        landmarks: LandmarkRenderFrame,
        landmarkIndex: Int,
        predictionSeconds: Float,
        fillTransform: FillCenterTransform,
        output: FloatArray,
        pointIndex: Int,
    ) {
        val normalizedX = landmarks.x(landmarkIndex, predictionSeconds)
        val normalizedY = landmarks.y(landmarkIndex, predictionSeconds)
        val outputIndex = pointIndex * POINT_SIZE
        output[outputIndex] = fillTransform.mapX(
            imageTransform.mapX(normalizedX, normalizedY),
            sourceWidth,
        )
        output[outputIndex + 1] = fillTransform.mapY(
            imageTransform.mapY(normalizedX, normalizedY),
            sourceHeight,
        )
    }

    private fun buildLipPath(path: Path, insetFraction: Float) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD

        interpolateContour(
            from = outerPoints,
            toward = innerPoints,
            fraction = insetFraction,
        )
        addClosedSpline(path, adjustedPoints)

        interpolateContour(
            from = innerPoints,
            toward = outerPoints,
            fraction = insetFraction,
        )
        addClosedSpline(path, adjustedPoints)
    }

    private fun interpolateContour(from: FloatArray, toward: FloatArray, fraction: Float) {
        for (index in from.indices) {
            adjustedPoints[index] = from[index] + (toward[index] - from[index]) * fraction
        }
    }

    private fun addClosedSpline(path: Path, points: FloatArray) {
        val pointCount = points.size / POINT_SIZE
        path.moveTo(points[0], points[1])
        for (pointIndex in 0 until pointCount) {
            val previous = (pointIndex - 1 + pointCount) % pointCount
            val current = pointIndex
            val next = (pointIndex + 1) % pointCount
            val afterNext = (pointIndex + 2) % pointCount
            val currentX = points[current * POINT_SIZE]
            val currentY = points[current * POINT_SIZE + 1]
            val nextX = points[next * POINT_SIZE]
            val nextY = points[next * POINT_SIZE + 1]
            val control1X = currentX +
                (nextX - points[previous * POINT_SIZE]) * SPLINE_CONTROL_FACTOR
            val control1Y = currentY +
                (nextY - points[previous * POINT_SIZE + 1]) * SPLINE_CONTROL_FACTOR
            val control2X = nextX -
                (points[afterNext * POINT_SIZE] - currentX) * SPLINE_CONTROL_FACTOR
            val control2Y = nextY -
                (points[afterNext * POINT_SIZE + 1] - currentY) * SPLINE_CONTROL_FACTOR
            path.cubicTo(control1X, control1Y, control2X, control2Y, nextX, nextY)
        }
        path.close()
    }

    private fun drawCoverage(canvas: Canvas, path: Path, coverage: Int) {
        colorPaint.alpha = coverage
        canvas.drawPath(path, colorPaint)
    }

    private fun Paint.configureColorBlend() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setModernColorBlend()
        } else {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
    }

    private fun Paint.configureMatteBlend() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setModernMatteBlend()
        } else {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun Paint.setModernColorBlend() {
        blendMode = BlendMode.COLOR
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun Paint.setModernMatteBlend() {
        blendMode = BlendMode.MULTIPLY
    }

    companion object {
        private const val POINT_SIZE = 2
        private const val MID_INSET_FRACTION = 0.055f
        private const val CORE_INSET_FRACTION = 0.12f
        private const val EDGE_COVERAGE = 42
        private const val MID_COVERAGE = 62
        private const val CORE_COVERAGE = 132
        private const val MATTE_COVERAGE = 24
        private const val SPLINE_CONTROL_FACTOR = 0.11f
        private val MAX_REQUIRED_LANDMARK_INDEX = maxOf(
            LipLandmarkTopology.outerContour.max(),
            LipLandmarkTopology.innerContour.max(),
        )
    }
}
