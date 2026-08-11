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
 * Hardware-accelerated, reference-calibrated matte lipstick prototype.
 *
 * COLOR blending retains camera luminance so natural lip folds and shading remain visible. The
 * upper and lower lip use separate pigment profiles, while a subtle MULTIPLY pass compresses only
 * the strongest highlights. The mouth and teeth are excluded from both regions.
 */
class LipstickOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val upperColorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lipstick_matte_upper)
        style = Paint.Style.FILL
        configureColorBlend()
    }
    private val lowerColorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lipstick_matte_lower)
        style = Paint.Style.FILL
        configureColorBlend()
    }
    private val mattePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lipstick_matte_shadow)
        style = Paint.Style.FILL
        configureMatteBlend()
    }

    private val materialPath = Path()
    private val outerPoints = FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val innerPoints = FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)
    private val insetOuterPoints = FloatArray(outerPoints.size)
    private val insetInnerPoints = FloatArray(innerPoints.size)
    private val regionPoints = FloatArray(REGION_POINT_COUNT * POINT_SIZE)

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
        drawLipMaterial(
            canvas = canvas,
            upperLip = true,
            colorPaint = upperColorPaint,
            profile = ReferenceMatteLipstickProfile.upper,
        )
        drawLipMaterial(
            canvas = canvas,
            upperLip = false,
            colorPaint = lowerColorPaint,
            profile = ReferenceMatteLipstickProfile.lower,
        )

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

    private fun drawLipMaterial(
        canvas: Canvas,
        upperLip: Boolean,
        colorPaint: Paint,
        profile: LipstickCoverageProfile,
    ) {
        buildLipRegionPath(
            materialPath,
            ReferenceMatteLipstickProfile.EDGE_INSET_FRACTION,
            upperLip,
        )
        drawCoverage(canvas, materialPath, colorPaint, profile.edgeCoverage)

        buildLipRegionPath(
            materialPath,
            ReferenceMatteLipstickProfile.MID_INSET_FRACTION,
            upperLip,
        )
        drawCoverage(canvas, materialPath, colorPaint, profile.midCoverage)

        buildLipRegionPath(
            materialPath,
            ReferenceMatteLipstickProfile.CORE_INSET_FRACTION,
            upperLip,
        )
        drawCoverage(canvas, materialPath, colorPaint, profile.coreCoverage)

        mattePaint.alpha = profile.matteCoverage
        canvas.drawPath(materialPath, mattePaint)
    }

    private fun buildLipRegionPath(path: Path, insetFraction: Float, upperLip: Boolean) {
        path.reset()
        interpolateContour(
            from = outerPoints,
            toward = innerPoints,
            fraction = insetFraction,
            output = insetOuterPoints,
        )
        interpolateContour(
            from = innerPoints,
            toward = outerPoints,
            fraction = insetFraction,
            output = insetInnerPoints,
        )

        if (upperLip) {
            buildUpperRegionPoints()
        } else {
            buildLowerRegionPoints()
        }
        addClosedSpline(path, regionPoints)
    }

    private fun interpolateContour(
        from: FloatArray,
        toward: FloatArray,
        fraction: Float,
        output: FloatArray,
    ) {
        for (index in from.indices) {
            output[index] = from[index] + (toward[index] - from[index]) * fraction
        }
    }

    private fun buildUpperRegionPoints() {
        var outputPointIndex = 0
        copyPoint(insetOuterPoints, 0, regionPoints, outputPointIndex++)
        for (sourcePointIndex in LAST_CONTOUR_POINT_INDEX downTo LIP_CORNER_POINT_INDEX) {
            copyPoint(insetOuterPoints, sourcePointIndex, regionPoints, outputPointIndex++)
        }
        for (sourcePointIndex in LIP_CORNER_POINT_INDEX..LAST_CONTOUR_POINT_INDEX) {
            copyPoint(insetInnerPoints, sourcePointIndex, regionPoints, outputPointIndex++)
        }
        copyPoint(insetInnerPoints, 0, regionPoints, outputPointIndex)
    }

    private fun buildLowerRegionPoints() {
        var outputPointIndex = 0
        for (sourcePointIndex in 0..LIP_CORNER_POINT_INDEX) {
            copyPoint(insetOuterPoints, sourcePointIndex, regionPoints, outputPointIndex++)
        }
        for (sourcePointIndex in LIP_CORNER_POINT_INDEX downTo 0) {
            copyPoint(insetInnerPoints, sourcePointIndex, regionPoints, outputPointIndex++)
        }
    }

    private fun copyPoint(
        source: FloatArray,
        sourcePointIndex: Int,
        output: FloatArray,
        outputPointIndex: Int,
    ) {
        val sourceIndex = sourcePointIndex * POINT_SIZE
        val outputIndex = outputPointIndex * POINT_SIZE
        output[outputIndex] = source[sourceIndex]
        output[outputIndex + 1] = source[sourceIndex + 1]
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

    private fun drawCoverage(canvas: Canvas, path: Path, paint: Paint, coverage: Int) {
        paint.alpha = coverage
        canvas.drawPath(path, paint)
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
        private const val LIP_CORNER_POINT_INDEX = 10
        private const val LAST_CONTOUR_POINT_INDEX = 19
        private const val REGION_POINT_COUNT = 22
        private const val SPLINE_CONTROL_FACTOR = 0.11f
        private val MAX_REQUIRED_LANDMARK_INDEX = maxOf(
            LipLandmarkTopology.outerContour.max(),
            LipLandmarkTopology.innerContour.max(),
        )
    }
}
