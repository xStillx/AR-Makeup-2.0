package com.example.armakeup.tracking

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Robust frame-to-frame similarity fit over rigid facial regions.
 *
 * A single unstable eye or nose landmark must not rotate or scale the whole lipstick mesh. The
 * estimator therefore uses a wider anchor set and three Huber IRLS passes. It reports residual
 * quality separately so the temporal predictor can become more conservative without switching
 * coordinate sources.
 */
internal class RobustSimilarityEstimator(
    private val anchorIndices: IntArray = DEFAULT_ANCHOR_INDICES,
) {
    private val weights = FloatArray(anchorIndices.size)
    private val residuals = FloatArray(anchorIndices.size)

    fun estimate(source: FloatArray, target: FloatArray): RobustSimilarityEstimate {
        if (
            source.size != target.size ||
            source.size % LandmarkRenderFrame.COORDINATE_COUNT != 0 ||
            source.size / LandmarkRenderFrame.COORDINATE_COUNT <= maximumAnchorIndex
        ) {
            return RobustSimilarityEstimate.INVALID
        }

        var validAnchorCount = 0
        anchorIndices.forEachIndexed { anchorOffset, landmarkIndex ->
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            val valid = source[coordinateIndex].isFinite() &&
                source[coordinateIndex + 1].isFinite() &&
                target[coordinateIndex].isFinite() &&
                target[coordinateIndex + 1].isFinite()
            weights[anchorOffset] = if (valid) 1f else 0f
            if (valid) validAnchorCount++
        }
        if (validAnchorCount < MINIMUM_VALID_ANCHORS) {
            return RobustSimilarityEstimate.INVALID
        }

        var fit = weightedFit(source, target) ?: return RobustSimilarityEstimate.INVALID
        repeat(IRLS_ITERATIONS) {
            val targetSpread = computeResidualsAndTargetSpread(source, target, fit)
            if (!targetSpread.isFinite() || targetSpread <= MINIMUM_SPREAD) {
                return RobustSimilarityEstimate.INVALID
            }
            val huberDelta = max(MINIMUM_HUBER_DELTA, targetSpread * HUBER_DELTA_FRACTION)
            anchorIndices.indices.forEach { anchorOffset ->
                if (weights[anchorOffset] <= 0f) return@forEach
                val residual = residuals[anchorOffset]
                weights[anchorOffset] = if (residual <= huberDelta) {
                    1f
                } else {
                    (huberDelta / residual).coerceAtLeast(MINIMUM_OUTLIER_WEIGHT)
                }
            }
            fit = weightedFit(source, target) ?: return RobustSimilarityEstimate.INVALID
        }

        val targetSpread = computeResidualsAndTargetSpread(source, target, fit)
        if (!targetSpread.isFinite() || targetSpread <= MINIMUM_SPREAD) {
            return RobustSimilarityEstimate.INVALID
        }
        val huberDelta = max(MINIMUM_HUBER_DELTA, targetSpread * HUBER_DELTA_FRACTION)
        var weightedSquaredResidual = 0.0
        var weightSum = 0.0
        var inlierCount = 0
        anchorIndices.indices.forEach { anchorOffset ->
            val weight = weights[anchorOffset]
            if (weight <= 0f) return@forEach
            val residual = residuals[anchorOffset]
            weightedSquaredResidual += weight * residual * residual
            weightSum += weight
            if (residual <= huberDelta * INLIER_DELTA_MULTIPLIER) inlierCount++
        }
        if (weightSum <= 0.0) return RobustSimilarityEstimate.INVALID

        val normalizedRmsResidual = (
            sqrt(weightedSquaredResidual / weightSum).toFloat() / targetSpread
        ).coerceAtLeast(0f)
        val inlierFraction = inlierCount.toFloat() / validAnchorCount
        val residualQuality = (
            1f - normalizedRmsResidual / MAXIMUM_USEFUL_NORMALIZED_RESIDUAL
        ).coerceIn(0f, 1f)
        val inlierQuality = (
            (inlierFraction - MINIMUM_USEFUL_INLIER_FRACTION) /
                (1f - MINIMUM_USEFUL_INLIER_FRACTION)
        ).coerceIn(0f, 1f)
        val quality = (
            RESIDUAL_QUALITY_WEIGHT * residualQuality +
                (1f - RESIDUAL_QUALITY_WEIGHT) * inlierQuality
        ).coerceIn(0f, 1f)

        return RobustSimilarityEstimate(
            sourceCenterX = fit.sourceCenterX,
            sourceCenterY = fit.sourceCenterY,
            targetCenterX = fit.targetCenterX,
            targetCenterY = fit.targetCenterY,
            scaleCosine = fit.scaleCosine,
            scaleSine = fit.scaleSine,
            normalizedRmsResidual = normalizedRmsResidual,
            inlierFraction = inlierFraction,
            quality = quality,
        )
    }

    private fun weightedFit(source: FloatArray, target: FloatArray): WeightedFit? {
        var weightSum = 0.0
        var sourceCenterX = 0.0
        var sourceCenterY = 0.0
        var targetCenterX = 0.0
        var targetCenterY = 0.0
        anchorIndices.forEachIndexed { anchorOffset, landmarkIndex ->
            val weight = weights[anchorOffset].toDouble()
            if (weight <= 0.0) return@forEachIndexed
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            weightSum += weight
            sourceCenterX += source[coordinateIndex] * weight
            sourceCenterY += source[coordinateIndex + 1] * weight
            targetCenterX += target[coordinateIndex] * weight
            targetCenterY += target[coordinateIndex + 1] * weight
        }
        if (weightSum < MINIMUM_VALID_ANCHORS) return null
        sourceCenterX /= weightSum
        sourceCenterY /= weightSum
        targetCenterX /= weightSum
        targetCenterY /= weightSum

        var denominator = 0.0
        var scaleCosineNumerator = 0.0
        var scaleSineNumerator = 0.0
        anchorIndices.forEachIndexed { anchorOffset, landmarkIndex ->
            val weight = weights[anchorOffset].toDouble()
            if (weight <= 0.0) return@forEachIndexed
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            val sourceX = source[coordinateIndex] - sourceCenterX
            val sourceY = source[coordinateIndex + 1] - sourceCenterY
            val targetX = target[coordinateIndex] - targetCenterX
            val targetY = target[coordinateIndex + 1] - targetCenterY
            denominator += weight * (sourceX * sourceX + sourceY * sourceY)
            scaleCosineNumerator += weight * (sourceX * targetX + sourceY * targetY)
            scaleSineNumerator += weight * (sourceX * targetY - sourceY * targetX)
        }
        if (denominator <= MINIMUM_DENOMINATOR) return null
        val scaleCosine = (scaleCosineNumerator / denominator).toFloat()
        val scaleSine = (scaleSineNumerator / denominator).toFloat()
        val scale = hypot(scaleCosine, scaleSine)
        if (!scale.isFinite() || scale !in MINIMUM_SCALE..MAXIMUM_SCALE) return null

        return WeightedFit(
            sourceCenterX = sourceCenterX.toFloat(),
            sourceCenterY = sourceCenterY.toFloat(),
            targetCenterX = targetCenterX.toFloat(),
            targetCenterY = targetCenterY.toFloat(),
            scaleCosine = scaleCosine,
            scaleSine = scaleSine,
        )
    }

    private fun computeResidualsAndTargetSpread(
        source: FloatArray,
        target: FloatArray,
        fit: WeightedFit,
    ): Float {
        var targetSquaredDistance = 0.0
        var weightSum = 0.0
        anchorIndices.forEachIndexed { anchorOffset, landmarkIndex ->
            val weight = weights[anchorOffset].toDouble()
            if (weight <= 0.0) {
                residuals[anchorOffset] = Float.POSITIVE_INFINITY
                return@forEachIndexed
            }
            val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
            val mappedX = fit.mapX(source[coordinateIndex], source[coordinateIndex + 1])
            val mappedY = fit.mapY(source[coordinateIndex], source[coordinateIndex + 1])
            val residualX = target[coordinateIndex] - mappedX
            val residualY = target[coordinateIndex + 1] - mappedY
            residuals[anchorOffset] = hypot(residualX, residualY)
            val targetX = target[coordinateIndex] - fit.targetCenterX
            val targetY = target[coordinateIndex + 1] - fit.targetCenterY
            targetSquaredDistance += weight * (targetX * targetX + targetY * targetY)
            weightSum += weight
        }
        return if (weightSum <= 0.0) {
            Float.NaN
        } else {
            sqrt(targetSquaredDistance / weightSum).toFloat()
        }
    }

    private data class WeightedFit(
        val sourceCenterX: Float,
        val sourceCenterY: Float,
        val targetCenterX: Float,
        val targetCenterY: Float,
        val scaleCosine: Float,
        val scaleSine: Float,
    ) {
        fun mapX(x: Float, y: Float): Float {
            val deltaX = x - sourceCenterX
            val deltaY = y - sourceCenterY
            return targetCenterX + scaleCosine * deltaX - scaleSine * deltaY
        }

        fun mapY(x: Float, y: Float): Float {
            val deltaX = x - sourceCenterX
            val deltaY = y - sourceCenterY
            return targetCenterY + scaleSine * deltaX + scaleCosine * deltaY
        }
    }

    private val maximumAnchorIndex = anchorIndices.maxOrNull() ?: Int.MAX_VALUE

    companion object {
        internal val DEFAULT_ANCHOR_INDICES = intArrayOf(
            10, 151, 9, 8, // central forehead
            168, 6, 197, 195, 5, 4, 1, 2, // nose ridge and nose body
            33, 133, 362, 263, // eye corners
            234, 454, 93, 323, 127, 356, // lateral cheeks and temples
        )
        private const val MINIMUM_VALID_ANCHORS = 6
        private const val IRLS_ITERATIONS = 3
        private const val MINIMUM_DENOMINATOR = 1e-8
        private const val MINIMUM_SPREAD = 1e-5f
        private const val MINIMUM_SCALE = 0.75f
        private const val MAXIMUM_SCALE = 1.25f
        private const val MINIMUM_HUBER_DELTA = 0.0015f
        private const val HUBER_DELTA_FRACTION = 0.025f
        private const val MINIMUM_OUTLIER_WEIGHT = 0.05f
        private const val INLIER_DELTA_MULTIPLIER = 2.5f
        private const val MAXIMUM_USEFUL_NORMALIZED_RESIDUAL = 0.08f
        private const val MINIMUM_USEFUL_INLIER_FRACTION = 0.45f
        private const val RESIDUAL_QUALITY_WEIGHT = 0.7f
    }
}

internal data class RobustSimilarityEstimate(
    val sourceCenterX: Float,
    val sourceCenterY: Float,
    val targetCenterX: Float,
    val targetCenterY: Float,
    val scaleCosine: Float,
    val scaleSine: Float,
    val normalizedRmsResidual: Float,
    val inlierFraction: Float,
    val quality: Float,
) {
    val isValid: Boolean
        get() = sourceCenterX.isFinite() && sourceCenterY.isFinite() &&
            targetCenterX.isFinite() && targetCenterY.isFinite() &&
            scaleCosine.isFinite() && scaleSine.isFinite() &&
            normalizedRmsResidual.isFinite() && inlierFraction.isFinite() && quality.isFinite()

    fun mapX(x: Float, y: Float): Float {
        val deltaX = x - sourceCenterX
        val deltaY = y - sourceCenterY
        return targetCenterX + scaleCosine * deltaX - scaleSine * deltaY
    }

    fun mapY(x: Float, y: Float): Float {
        val deltaX = x - sourceCenterX
        val deltaY = y - sourceCenterY
        return targetCenterY + scaleSine * deltaX + scaleCosine * deltaY
    }

    companion object {
        val INVALID = RobustSimilarityEstimate(
            sourceCenterX = Float.NaN,
            sourceCenterY = Float.NaN,
            targetCenterX = Float.NaN,
            targetCenterY = Float.NaN,
            scaleCosine = Float.NaN,
            scaleSine = Float.NaN,
            normalizedRmsResidual = Float.NaN,
            inlierFraction = Float.NaN,
            quality = Float.NaN,
        )
    }
}
