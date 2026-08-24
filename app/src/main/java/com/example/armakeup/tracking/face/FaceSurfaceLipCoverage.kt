package com.example.armakeup.tracking.face

import kotlin.math.sqrt

/** Builds a feathered canonical-UV lip-band mask on the shared face vertices. */
internal object FaceSurfaceLipCoverage {

    fun build(
        textureCoordinates: FaceSurfaceTextureCoordinates,
        outerContour: IntArray,
        innerContour: IntArray,
    ): FloatArray {
        val pointCount = textureCoordinates.pointCount
        val outer = outerContour.filter { it in 0 until pointCount }.toIntArray()
        val inner = innerContour.filter { it in 0 until pointCount }.toIntArray()
        if (outer.size < MINIMUM_POLYGON_POINTS || inner.size < MINIMUM_POLYGON_POINTS) {
            return FloatArray(pointCount)
        }

        val boundary = BooleanArray(pointCount)
        outer.forEach { boundary[it] = true }
        inner.forEach { boundary[it] = true }
        val medianBandWidth = matchedBandWidths(textureCoordinates, outer, inner)
            .sortedArray()
            .let { widths ->
                if (widths.isEmpty()) 0f else widths[widths.size / 2]
            }
        if (!medianBandWidth.isFinite() || medianBandWidth <= MINIMUM_DISTANCE) {
            return FloatArray(pointCount)
        }
        val featherWidth = medianBandWidth * FEATHER_FRACTION_OF_BAND

        return FloatArray(pointCount) { index ->
            if (boundary[index]) return@FloatArray 0f
            val u = textureCoordinates.u(index)
            val v = textureCoordinates.v(index)
            if (!contains(outer, textureCoordinates, u, v) ||
                contains(inner, textureCoordinates, u, v)
            ) {
                return@FloatArray 0f
            }
            val edgeDistance = minOf(
                distanceToLoop(outer, textureCoordinates, u, v),
                distanceToLoop(inner, textureCoordinates, u, v),
            )
            // The existing diagnostic shader expands coverage by four. Keep this mask in the same
            // input domain while making alpha reach one after one geometry-derived feather width.
            (edgeDistance / (featherWidth * SHADER_COVERAGE_SCALE))
                .coerceIn(0f, 1f / SHADER_COVERAGE_SCALE)
        }
    }

    private fun matchedBandWidths(
        textureCoordinates: FaceSurfaceTextureCoordinates,
        outer: IntArray,
        inner: IntArray,
    ): FloatArray {
        val count = minOf(outer.size, inner.size)
        return FloatArray(count) { index ->
            distance(
                textureCoordinates.u(outer[index]),
                textureCoordinates.v(outer[index]),
                textureCoordinates.u(inner[index]),
                textureCoordinates.v(inner[index]),
            )
        }.filter { it.isFinite() && it > MINIMUM_DISTANCE }.toFloatArray()
    }

    private fun contains(
        polygon: IntArray,
        textureCoordinates: FaceSurfaceTextureCoordinates,
        u: Float,
        v: Float,
    ): Boolean {
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            val currentU = textureCoordinates.u(current)
            val currentV = textureCoordinates.v(current)
            val previousU = textureCoordinates.u(previous)
            val previousV = textureCoordinates.v(previous)
            if ((currentV > v) != (previousV > v)) {
                val intersectionU = (previousU - currentU) * (v - currentV) /
                    (previousV - currentV) + currentU
                if (u < intersectionU) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun distanceToLoop(
        polygon: IntArray,
        textureCoordinates: FaceSurfaceTextureCoordinates,
        u: Float,
        v: Float,
    ): Float {
        var minimum = Float.POSITIVE_INFINITY
        var previous = polygon.last()
        polygon.forEach { current ->
            minimum = minOf(
                minimum,
                distanceToSegment(
                    u = u,
                    v = v,
                    firstU = textureCoordinates.u(previous),
                    firstV = textureCoordinates.v(previous),
                    secondU = textureCoordinates.u(current),
                    secondV = textureCoordinates.v(current),
                ),
            )
            previous = current
        }
        return minimum
    }

    private fun distanceToSegment(
        u: Float,
        v: Float,
        firstU: Float,
        firstV: Float,
        secondU: Float,
        secondV: Float,
    ): Float {
        val edgeU = secondU - firstU
        val edgeV = secondV - firstV
        val lengthSquared = edgeU * edgeU + edgeV * edgeV
        if (lengthSquared <= MINIMUM_DISTANCE * MINIMUM_DISTANCE) {
            return distance(u, v, firstU, firstV)
        }
        val fraction = (((u - firstU) * edgeU + (v - firstV) * edgeV) / lengthSquared)
            .coerceIn(0f, 1f)
        return distance(u, v, firstU + edgeU * fraction, firstV + edgeV * fraction)
    }

    private fun distance(firstU: Float, firstV: Float, secondU: Float, secondV: Float): Float {
        val deltaU = secondU - firstU
        val deltaV = secondV - firstV
        return sqrt(deltaU * deltaU + deltaV * deltaV)
    }

    private const val MINIMUM_POLYGON_POINTS = 3
    private const val MINIMUM_DISTANCE = 1e-7f
    private const val FEATHER_FRACTION_OF_BAND = 0.35f
    private const val SHADER_COVERAGE_SCALE = 4f
}
