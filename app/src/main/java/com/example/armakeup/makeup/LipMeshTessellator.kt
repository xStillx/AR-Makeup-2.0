package com.example.armakeup.makeup

/**
 * Converts the paired MediaPipe lip contours into two feathered triangle strips.
 *
 * Each lip is represented by several rings between its outer and inner contour. Coverage grows
 * toward the lip core and returns to zero at the mouth boundary, so the oral cavity and teeth are
 * never part of the rendered mesh.
 */
internal class LipMeshTessellator(
    private val subdivisionsPerSegment: Int = DEFAULT_SUBDIVISIONS_PER_SEGMENT,
) {
    init {
        require(subdivisionsPerSegment > 0)
    }

    val samplesPerLip: Int = ARC_SEGMENT_COUNT * subdivisionsPerSegment + 1
    val verticesPerLip: Int = samplesPerLip * RING_FRACTIONS.size
    val vertexCount: Int = verticesPerLip * LIP_COUNT
    val indicesPerLip: Int = (samplesPerLip - 1) * (RING_FRACTIONS.size - 1) * INDICES_PER_QUAD
    val indexCount: Int = indicesPerLip * LIP_COUNT
    val indices: ShortArray = buildIndices()

    /** Interleaved display-normalized x, y and pigment coverage. */
    fun tessellate(
        outerContour: FloatArray,
        innerContour: FloatArray,
        upperProfile: LipstickCoverageProfile,
        lowerProfile: LipstickCoverageProfile,
    ): FloatArray {
        require(outerContour.size == CONTOUR_POINT_COUNT * POINT_SIZE)
        require(innerContour.size == CONTOUR_POINT_COUNT * POINT_SIZE)

        val output = FloatArray(vertexCount * VERTEX_COMPONENT_COUNT)
        writeLip(
            output = output,
            vertexOffset = 0,
            outerArc = sampleArc(outerContour, UPPER_ARC_INDICES),
            innerArc = sampleArc(innerContour, UPPER_ARC_INDICES),
            profile = upperProfile,
        )
        writeLip(
            output = output,
            vertexOffset = verticesPerLip,
            outerArc = sampleArc(outerContour, LOWER_ARC_INDICES),
            innerArc = sampleArc(innerContour, LOWER_ARC_INDICES),
            profile = lowerProfile,
        )
        return output
    }

    private fun writeLip(
        output: FloatArray,
        vertexOffset: Int,
        outerArc: FloatArray,
        innerArc: FloatArray,
        profile: LipstickCoverageProfile,
    ) {
        val coverage = coverageRings(profile)
        for (sampleIndex in 0 until samplesPerLip) {
            val pointIndex = sampleIndex * POINT_SIZE
            val outerX = outerArc[pointIndex]
            val outerY = outerArc[pointIndex + 1]
            val innerX = innerArc[pointIndex]
            val innerY = innerArc[pointIndex + 1]
            RING_FRACTIONS.forEachIndexed { ringIndex, fraction ->
                val vertexIndex = vertexOffset + sampleIndex * RING_FRACTIONS.size + ringIndex
                val outputIndex = vertexIndex * VERTEX_COMPONENT_COUNT
                output[outputIndex] = outerX + (innerX - outerX) * fraction
                output[outputIndex + 1] = outerY + (innerY - outerY) * fraction
                output[outputIndex + 2] = coverage[ringIndex]
            }
        }
    }

    private fun sampleArc(contour: FloatArray, indices: IntArray): FloatArray {
        val sampled = FloatArray(samplesPerLip * POINT_SIZE)
        var outputPoint = 0
        for (segment in 0 until indices.lastIndex) {
            val previous = indices[(segment - 1).coerceAtLeast(0)]
            val current = indices[segment]
            val next = indices[segment + 1]
            val afterNext = indices[(segment + 2).coerceAtMost(indices.lastIndex)]
            for (subdivision in 0 until subdivisionsPerSegment) {
                val t = subdivision.toFloat() / subdivisionsPerSegment
                writeCubicPoint(
                    contour,
                    previous,
                    current,
                    next,
                    afterNext,
                    t,
                    sampled,
                    outputPoint++,
                )
            }
        }
        copyPoint(contour, indices.last(), sampled, outputPoint)
        return sampled
    }

    private fun writeCubicPoint(
        contour: FloatArray,
        previous: Int,
        current: Int,
        next: Int,
        afterNext: Int,
        t: Float,
        output: FloatArray,
        outputPoint: Int,
    ) {
        val currentIndex = current * POINT_SIZE
        val nextIndex = next * POINT_SIZE
        val previousIndex = previous * POINT_SIZE
        val afterNextIndex = afterNext * POINT_SIZE
        val outputIndex = outputPoint * POINT_SIZE
        for (axis in 0 until POINT_SIZE) {
            val p0 = contour[currentIndex + axis]
            val p1 = contour[nextIndex + axis]
            val control0 = p0 +
                (p1 - contour[previousIndex + axis]) * SPLINE_CONTROL_FACTOR
            val control1 = p1 -
                (contour[afterNextIndex + axis] - p0) * SPLINE_CONTROL_FACTOR
            val inverseT = 1f - t
            output[outputIndex + axis] =
                inverseT * inverseT * inverseT * p0 +
                    3f * inverseT * inverseT * t * control0 +
                    3f * inverseT * t * t * control1 +
                    t * t * t * p1
        }
    }

    private fun copyPoint(
        source: FloatArray,
        sourcePoint: Int,
        output: FloatArray,
        outputPoint: Int,
    ) {
        val sourceIndex = sourcePoint * POINT_SIZE
        val outputIndex = outputPoint * POINT_SIZE
        output[outputIndex] = source[sourceIndex]
        output[outputIndex + 1] = source[sourceIndex + 1]
    }

    private fun coverageRings(profile: LipstickCoverageProfile): FloatArray {
        val edge = profile.edgeCoverage / MAX_ALPHA
        val mid = 1f - (1f - edge) * (1f - profile.midCoverage / MAX_ALPHA)
        val core = profile.effectiveCoreCoverage
        return floatArrayOf(0f, edge, mid, core, core, mid, edge, 0f)
    }

    private fun buildIndices(): ShortArray {
        require(vertexCount <= UShort.MAX_VALUE.toInt())
        val output = ShortArray(indexCount)
        var outputIndex = 0
        for (lipIndex in 0 until LIP_COUNT) {
            val lipVertexOffset = lipIndex * verticesPerLip
            for (sampleIndex in 0 until samplesPerLip - 1) {
                for (ringIndex in 0 until RING_FRACTIONS.size - 1) {
                    val topLeft = lipVertexOffset + sampleIndex * RING_FRACTIONS.size + ringIndex
                    val bottomLeft = topLeft + RING_FRACTIONS.size
                    val bottomRight = bottomLeft + 1
                    val topRight = topLeft + 1
                    output[outputIndex++] = topLeft.toShort()
                    output[outputIndex++] = bottomLeft.toShort()
                    output[outputIndex++] = bottomRight.toShort()
                    output[outputIndex++] = topLeft.toShort()
                    output[outputIndex++] = bottomRight.toShort()
                    output[outputIndex++] = topRight.toShort()
                }
            }
        }
        return output
    }

    companion object {
        const val VERTEX_COMPONENT_COUNT = 3
        private const val POINT_SIZE = 2
        private const val CONTOUR_POINT_COUNT = 20
        private const val ARC_SEGMENT_COUNT = 10
        private const val LIP_COUNT = 2
        private const val INDICES_PER_QUAD = 6
        private const val DEFAULT_SUBDIVISIONS_PER_SEGMENT = 4
        private const val SPLINE_CONTROL_FACTOR = 0.11f
        private const val MAX_ALPHA = 255f
        private val UPPER_ARC_INDICES = intArrayOf(0, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10)
        private val LOWER_ARC_INDICES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        private val RING_FRACTIONS = floatArrayOf(
            0f,
            ReferenceMatteLipstickProfile.EDGE_INSET_FRACTION,
            ReferenceMatteLipstickProfile.MID_INSET_FRACTION,
            ReferenceMatteLipstickProfile.CORE_INSET_FRACTION,
            1f - ReferenceMatteLipstickProfile.CORE_INSET_FRACTION,
            1f - ReferenceMatteLipstickProfile.MID_INSET_FRACTION,
            1f - ReferenceMatteLipstickProfile.EDGE_INSET_FRACTION,
            1f,
        )
    }
}
