package com.example.armakeup.arcore

import kotlin.math.sqrt

/** Maps stale ML image coordinates into the current ARCore face projection. */
internal data class FaceLocalAffineTransform(
    val xFromX: Float,
    val xFromY: Float,
    val xOffset: Float,
    val yFromX: Float,
    val yFromY: Float,
    val yOffset: Float,
    val normalizedRmsResidual: Float,
) {
    fun mapX(x: Float, y: Float): Float = xFromX * x + xFromY * y + xOffset

    fun mapY(x: Float, y: Float): Float = yFromX * x + yFromY * y + yOffset

    companion object {
        fun estimate(source: FloatArray, target: FloatArray): FaceLocalAffineTransform? {
            require(source.size == target.size)
            require(source.size % 2 == 0)
            val pointCount = source.size / 2
            if (pointCount < 3) return null

            var xx = 0.0
            var xy = 0.0
            var x = 0.0
            var yy = 0.0
            var y = 0.0
            var targetXFromX = 0.0
            var targetXFromY = 0.0
            var targetX = 0.0
            var targetYFromX = 0.0
            var targetYFromY = 0.0
            var targetY = 0.0
            repeat(pointCount) { pointIndex ->
                val index = pointIndex * 2
                val sourceX = source[index].toDouble()
                val sourceY = source[index + 1].toDouble()
                val destinationX = target[index].toDouble()
                val destinationY = target[index + 1].toDouble()
                if (
                    !sourceX.isFinite() || !sourceY.isFinite() ||
                    !destinationX.isFinite() || !destinationY.isFinite()
                ) {
                    return null
                }
                xx += sourceX * sourceX
                xy += sourceX * sourceY
                x += sourceX
                yy += sourceY * sourceY
                y += sourceY
                targetXFromX += sourceX * destinationX
                targetXFromY += sourceY * destinationX
                targetX += destinationX
                targetYFromX += sourceX * destinationY
                targetYFromY += sourceY * destinationY
                targetY += destinationY
            }

            val normal = arrayOf(
                doubleArrayOf(xx, xy, x),
                doubleArrayOf(xy, yy, y),
                doubleArrayOf(x, y, pointCount.toDouble()),
            )
            val coefficientsX = solve3x3(
                normal,
                doubleArrayOf(targetXFromX, targetXFromY, targetX),
            ) ?: return null
            val coefficientsY = solve3x3(
                normal,
                doubleArrayOf(targetYFromX, targetYFromY, targetY),
            ) ?: return null

            var squaredResidual = 0.0
            repeat(pointCount) { pointIndex ->
                val index = pointIndex * 2
                val sourceX = source[index].toDouble()
                val sourceY = source[index + 1].toDouble()
                val mappedX = coefficientsX[0] * sourceX +
                    coefficientsX[1] * sourceY + coefficientsX[2]
                val mappedY = coefficientsY[0] * sourceX +
                    coefficientsY[1] * sourceY + coefficientsY[2]
                val deltaX = mappedX - target[index]
                val deltaY = mappedY - target[index + 1]
                squaredResidual += deltaX * deltaX + deltaY * deltaY
            }

            return FaceLocalAffineTransform(
                xFromX = coefficientsX[0].toFloat(),
                xFromY = coefficientsX[1].toFloat(),
                xOffset = coefficientsX[2].toFloat(),
                yFromX = coefficientsY[0].toFloat(),
                yFromY = coefficientsY[1].toFloat(),
                yOffset = coefficientsY[2].toFloat(),
                normalizedRmsResidual = sqrt(squaredResidual / pointCount).toFloat(),
            )
        }

        private fun solve3x3(matrix: Array<DoubleArray>, rightHandSide: DoubleArray): DoubleArray? {
            val augmented = Array(3) { row ->
                doubleArrayOf(
                    matrix[row][0],
                    matrix[row][1],
                    matrix[row][2],
                    rightHandSide[row],
                )
            }
            repeat(3) { column ->
                var pivotRow = column
                for (candidate in column + 1 until 3) {
                    if (kotlin.math.abs(augmented[candidate][column]) >
                        kotlin.math.abs(augmented[pivotRow][column])
                    ) {
                        pivotRow = candidate
                    }
                }
                if (kotlin.math.abs(augmented[pivotRow][column]) < MINIMUM_PIVOT) return null
                if (pivotRow != column) {
                    val swap = augmented[pivotRow]
                    augmented[pivotRow] = augmented[column]
                    augmented[column] = swap
                }
                val pivot = augmented[column][column]
                for (entry in column until 4) augmented[column][entry] /= pivot
                repeat(3) { row ->
                    if (row == column) return@repeat
                    val factor = augmented[row][column]
                    for (entry in column until 4) {
                        augmented[row][entry] -= factor * augmented[column][entry]
                    }
                }
            }
            return doubleArrayOf(augmented[0][3], augmented[1][3], augmented[2][3])
        }

        private const val MINIMUM_PIVOT = 1e-10
    }
}
