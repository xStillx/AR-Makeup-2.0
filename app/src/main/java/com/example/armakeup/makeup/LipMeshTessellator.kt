package com.example.armakeup.makeup

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts the paired MediaPipe lip contours into two feathered triangle strips.
 *
 * Each lip is represented by several rings between its outer and inner contour. The mesh ends
 * at the inner contour, excluding the mouth aperture independently of coverage. Inner feathering
 * is optional: the ARCore renderer keeps pigment on the lip side of that boundary to avoid a seam.
 */
internal class LipMeshTessellator(
    private val subdivisionsPerSegment: Int = DEFAULT_SUBDIVISIONS_PER_SEGMENT,
    private val featherInnerBoundary: Boolean = true,
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

    private val surfacePositionScratch = FloatArray(verticesPerLip * SURFACE_POSITION_COMPONENT_COUNT)

    /**
     * Interleaved display x/y, reconstructed normal, coverage, outer-to-inner coordinate and arc.
     */
    fun tessellate(
        outerContour: FloatArray,
        innerContour: FloatArray,
        upperProfile: LipstickCoverageProfile,
        lowerProfile: LipstickCoverageProfile,
        outerCarrierExpansion: Float = 0f,
        innerSeamExpansion: Float = 0f,
        innerCarrierFeather: Float = 0f,
    ): FloatArray {
        require(outerContour.size == CONTOUR_POINT_COUNT * POINT_SIZE)
        require(innerContour.size == CONTOUR_POINT_COUNT * POINT_SIZE)
        require(outerCarrierExpansion in 0f..MAX_BOUNDARY_EXPANSION)
        require(innerSeamExpansion in 0f..MAX_BOUNDARY_EXPANSION)
        require(innerCarrierFeather in 0f..1f)

        val upperOuterArc = sampleArc(outerContour, UPPER_ARC_INDICES)
        val upperInnerArc = sampleArc(innerContour, UPPER_ARC_INDICES)
        val lowerOuterArc = sampleArc(outerContour, LOWER_ARC_INDICES)
        val lowerInnerArc = sampleArc(innerContour, LOWER_ARC_INDICES)
        expandBoundaries(upperOuterArc, upperInnerArc, outerCarrierExpansion, innerSeamExpansion)
        expandBoundaries(lowerOuterArc, lowerInnerArc, outerCarrierExpansion, innerSeamExpansion)

        val output = FloatArray(vertexCount * VERTEX_COMPONENT_COUNT)
        writeLip(
            output = output,
            vertexOffset = 0,
            outerArc = upperOuterArc,
            innerArc = upperInnerArc,
            profile = upperProfile,
            outerExpansion = outerCarrierExpansion,
            innerExpansion = innerSeamExpansion,
            innerCarrierFeather = innerCarrierFeather,
        )
        writeLip(
            output = output,
            vertexOffset = verticesPerLip,
            outerArc = lowerOuterArc,
            innerArc = lowerInnerArc,
            profile = lowerProfile,
            outerExpansion = outerCarrierExpansion,
            innerExpansion = innerSeamExpansion,
            innerCarrierFeather = innerCarrierFeather,
        )
        return output
    }

    /**
     * Adds a feathered carrier outside the measured vermilion border and a small lip-side overlap
     * at the mouth seam. Both displacements follow the local outer-to-inner direction and vanish at
     * the corners, so MediaPipe topology, point order and mouth aperture remain unchanged.
     */
    private fun expandBoundaries(
        outerArc: FloatArray,
        innerArc: FloatArray,
        outerExpansion: Float,
        innerExpansion: Float,
    ) {
        if (outerExpansion <= 0f && innerExpansion <= 0f) return
        repeat(samplesPerLip) { sampleIndex ->
            val point = sampleIndex * POINT_SIZE
            val acrossX = innerArc[point] - outerArc[point]
            val acrossY = innerArc[point + 1] - outerArc[point + 1]
            val arc = sampleIndex.toFloat() / (samplesPerLip - 1)
            val centerWeight = sin(PI.toFloat() * arc).coerceAtLeast(0f).pow(ARC_BULGE_POWER)
            outerArc[point] -= acrossX * outerExpansion * centerWeight
            outerArc[point + 1] -= acrossY * outerExpansion * centerWeight
            innerArc[point] += acrossX * innerExpansion * centerWeight
            innerArc[point + 1] += acrossY * innerExpansion * centerWeight
        }
    }

    private fun writeLip(
        output: FloatArray,
        vertexOffset: Int,
        outerArc: FloatArray,
        innerArc: FloatArray,
        profile: LipstickCoverageProfile,
        outerExpansion: Float,
        innerExpansion: Float,
        innerCarrierFeather: Float,
    ) {
        val coverage = coverageRings(
            profile = profile,
            outerExpansion = outerExpansion,
            innerExpansion = innerExpansion,
            innerCarrierFeather = innerCarrierFeather,
        )
        writeSurfacePositions(outerArc, innerArc)
        for (sampleIndex in 0 until samplesPerLip) {
            RING_FRACTIONS.forEachIndexed { ringIndex, fraction ->
                val vertexIndex = vertexOffset + sampleIndex * RING_FRACTIONS.size + ringIndex
                val outputIndex = vertexIndex * VERTEX_COMPONENT_COUNT
                val surfaceIndex = surfaceIndex(sampleIndex, ringIndex)
                output[outputIndex + X_COMPONENT_OFFSET] = surfacePositionScratch[surfaceIndex]
                output[outputIndex + Y_COMPONENT_OFFSET] = surfacePositionScratch[surfaceIndex + 1]
                writeSurfaceNormal(output, outputIndex, sampleIndex, ringIndex)
                output[outputIndex + COVERAGE_COMPONENT_OFFSET] = coverage[ringIndex]
                output[outputIndex + RING_COMPONENT_OFFSET] = fraction
                output[outputIndex + ARC_COMPONENT_OFFSET] =
                    sampleIndex.toFloat() / (samplesPerLip - 1)
            }
        }
    }

    private fun writeSurfacePositions(outerArc: FloatArray, innerArc: FloatArray) {
        for (sampleIndex in 0 until samplesPerLip) {
            val pointIndex = sampleIndex * POINT_SIZE
            val outerX = outerArc[pointIndex]
            val outerY = outerArc[pointIndex + 1]
            val innerX = innerArc[pointIndex]
            val innerY = innerArc[pointIndex + 1]
            val lipThickness = hypot(innerX - outerX, innerY - outerY)
            val arc = sampleIndex.toFloat() / (samplesPerLip - 1)
            val arcBulge = sin(PI.toFloat() * arc).coerceAtLeast(0f).pow(ARC_BULGE_POWER)
            RING_FRACTIONS.forEachIndexed { ringIndex, fraction ->
                val index = surfaceIndex(sampleIndex, ringIndex)
                surfacePositionScratch[index] = outerX + (innerX - outerX) * fraction
                surfacePositionScratch[index + 1] = outerY + (innerY - outerY) * fraction
                surfacePositionScratch[index + 2] = lipThickness * SURFACE_BULGE_SCALE *
                    arcBulge * sin(PI.toFloat() * fraction).coerceAtLeast(0f)
            }
        }
    }

    /** Central-difference normal of the procedural lip surface, expressed in render x/right y/up. */
    private fun writeSurfaceNormal(
        output: FloatArray,
        outputIndex: Int,
        sampleIndex: Int,
        ringIndex: Int,
    ) {
        val previousSample = (sampleIndex - 1).coerceAtLeast(0)
        val nextSample = (sampleIndex + 1).coerceAtMost(samplesPerLip - 1)
        val previousRing = (ringIndex - 1).coerceAtLeast(0)
        val nextRing = (ringIndex + 1).coerceAtMost(RING_FRACTIONS.lastIndex)
        val left = surfaceIndex(previousSample, ringIndex)
        val right = surfaceIndex(nextSample, ringIndex)
        val outer = surfaceIndex(sampleIndex, previousRing)
        val inner = surfaceIndex(sampleIndex, nextRing)

        val tangentX = surfacePositionScratch[right] - surfacePositionScratch[left]
        val tangentY = -(surfacePositionScratch[right + 1] - surfacePositionScratch[left + 1])
        val tangentZ = surfacePositionScratch[right + 2] - surfacePositionScratch[left + 2]
        val acrossX = surfacePositionScratch[inner] - surfacePositionScratch[outer]
        val acrossY = -(surfacePositionScratch[inner + 1] - surfacePositionScratch[outer + 1])
        val acrossZ = surfacePositionScratch[inner + 2] - surfacePositionScratch[outer + 2]

        var normalX = tangentY * acrossZ - tangentZ * acrossY
        var normalY = tangentZ * acrossX - tangentX * acrossZ
        var normalZ = tangentX * acrossY - tangentY * acrossX
        if (normalZ < 0f) {
            normalX = -normalX
            normalY = -normalY
            normalZ = -normalZ
        }
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        if (length <= MIN_NORMAL_LENGTH || !length.isFinite()) {
            normalX = 0f
            normalY = 0f
            normalZ = 1f
        } else {
            normalX /= length
            normalY /= length
            normalZ /= length
        }
        output[outputIndex + NORMAL_X_COMPONENT_OFFSET] = normalX
        output[outputIndex + NORMAL_Y_COMPONENT_OFFSET] = normalY
        output[outputIndex + NORMAL_Z_COMPONENT_OFFSET] = normalZ
    }

    private fun surfaceIndex(sampleIndex: Int, ringIndex: Int): Int =
        (sampleIndex * RING_FRACTIONS.size + ringIndex) * SURFACE_POSITION_COMPONENT_COUNT

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

    private fun coverageRings(
        profile: LipstickCoverageProfile,
        outerExpansion: Float,
        innerExpansion: Float,
        innerCarrierFeather: Float,
    ): FloatArray {
        val edge = profile.edgeCoverage / MAX_ALPHA
        val mid = 1f - (1f - edge) * (1f - profile.midCoverage / MAX_ALPHA)
        val core = profile.effectiveCoreCoverage
        // Preserve the outer feather and keep full coverage through the measured inner contour.
        val coverage = if (featherInnerBoundary) {
            floatArrayOf(0f, edge, mid, core, core, mid, edge, 0f)
        } else {
            floatArrayOf(0f, edge, mid, core, core, core, core, core)
        }
        if (innerExpansion <= 0f || innerCarrierFeather <= 0f) return coverage

        // The original inner landmark lies inside the expanded strip. Keep the first part of the
        // carrier opaque, then fade only its extra mouth-side tail to avoid painting teeth/cavity.
        val originalInnerFraction =
            (1f + outerExpansion) / (1f + outerExpansion + innerExpansion)
        val fadeStart = originalInnerFraction +
            (1f - originalInnerFraction) * INNER_CARRIER_SOLID_FRACTION
        RING_FRACTIONS.forEachIndexed { ringIndex, fraction ->
            if (fraction <= fadeStart) return@forEachIndexed
            val fadeProgress = ((fraction - fadeStart) / (1f - fadeStart)).coerceIn(0f, 1f)
            val smoothProgress = fadeProgress * fadeProgress * (3f - 2f * fadeProgress)
            val featherCoverage = 1f - smoothProgress
            val carrierScale = 1f + (featherCoverage - 1f) * innerCarrierFeather
            coverage[ringIndex] *= carrierScale
        }
        return coverage
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
        const val VERTEX_COMPONENT_COUNT = 8
        const val X_COMPONENT_OFFSET = 0
        const val Y_COMPONENT_OFFSET = 1
        const val NORMAL_X_COMPONENT_OFFSET = 2
        const val NORMAL_Y_COMPONENT_OFFSET = 3
        const val NORMAL_Z_COMPONENT_OFFSET = 4
        const val COVERAGE_COMPONENT_OFFSET = 5
        const val RING_COMPONENT_OFFSET = 6
        const val ARC_COMPONENT_OFFSET = 7
        private const val POINT_SIZE = 2
        private const val SURFACE_POSITION_COMPONENT_COUNT = 3
        private const val CONTOUR_POINT_COUNT = 20
        private const val ARC_SEGMENT_COUNT = 10
        private const val LIP_COUNT = 2
        private const val INDICES_PER_QUAD = 6
        private const val DEFAULT_SUBDIVISIONS_PER_SEGMENT = 4
        private const val SPLINE_CONTROL_FACTOR = 0.11f
        private const val MAX_ALPHA = 255f
        private const val SURFACE_BULGE_SCALE = 0.72f
        private const val ARC_BULGE_POWER = 0.65f
        private const val MAX_BOUNDARY_EXPANSION = 0.35f
        private const val INNER_CARRIER_SOLID_FRACTION = 0.40f
        private const val MIN_NORMAL_LENGTH = 1e-7f
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
