package com.example.armakeup.tracking.face

import kotlin.math.sqrt

/** Packs the current outer/inner lip loops for analytic fragment-stage coverage. */
internal object DynamicLipContourCoverage {

    private const val VALUES_PER_POINT = 4
    private const val PARAMETER_COUNT = 4
    private const val FEATHER_FRACTION_OF_BAND = 0.35f
    private const val MINIMUM_FEATHER_WIDTH = 1e-5f

    const val POINT_COUNT = 20
    const val FLOAT_COUNT = POINT_COUNT * VALUES_PER_POINT + PARAMETER_COUNT
    const val OPACITY_INDEX = POINT_COUNT * VALUES_PER_POINT + 1

    fun write(
        outer: FaceRegionGeometry,
        inner: FaceRegionGeometry,
        opacity: Float,
        destination: FloatArray,
    ) {
        require(outer.region == FaceRegion.LIPS_OUTER)
        require(inner.region == FaceRegion.LIPS_INNER)
        require(outer.pointCount == POINT_COUNT)
        require(inner.pointCount == POINT_COUNT)
        require(opacity in 0f..1f)
        require(destination.size == FLOAT_COUNT)

        val bandWidths = FloatArray(POINT_COUNT)
        repeat(POINT_COUNT) { index ->
            val destinationIndex = index * VALUES_PER_POINT
            destination[destinationIndex] = outer.x(index)
            destination[destinationIndex + 1] = outer.y(index)
            destination[destinationIndex + 2] = inner.x(index)
            destination[destinationIndex + 3] = inner.y(index)
            val deltaX = outer.x(index) - inner.x(index)
            val deltaY = outer.y(index) - inner.y(index)
            bandWidths[index] = sqrt(deltaX * deltaX + deltaY * deltaY)
        }
        val medianBandWidth = bandWidths.sortedArray()[POINT_COUNT / 2]
        val parameterIndex = POINT_COUNT * VALUES_PER_POINT
        destination[parameterIndex] = 1f
        destination[parameterIndex + 1] = opacity
        destination[parameterIndex + 2] = maxOf(
            medianBandWidth * FEATHER_FRACTION_OF_BAND,
            MINIMUM_FEATHER_WIDTH,
        )
        destination[parameterIndex + 3] = POINT_COUNT.toFloat()
    }
}
