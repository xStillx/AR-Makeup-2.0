package com.example.armakeup.makeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LipMeshTessellatorTest {

    private val tessellator = LipMeshTessellator(subdivisionsPerSegment = 2)

    @Test
    fun tessellationHasStableGpuBufferSizes() {
        val vertices = tessellate()

        assertEquals(
            tessellator.vertexCount * LipMeshTessellator.VERTEX_COMPONENT_COUNT,
            vertices.size,
        )
        assertEquals(tessellator.indexCount, tessellator.indices.size)
        assertTrue(tessellator.indices.all { it.toInt() in 0 until tessellator.vertexCount })
    }

    @Test
    fun upperAndLowerPrimitivesUseDisjointVertexRanges() {
        val upperIndices = tessellator.indices.take(tessellator.indicesPerLip)
        val lowerIndices = tessellator.indices.drop(tessellator.indicesPerLip)

        assertTrue(upperIndices.all { it.toInt() < tessellator.verticesPerLip })
        assertTrue(lowerIndices.all { it.toInt() >= tessellator.verticesPerLip })
    }

    @Test
    fun coverageFadesToZeroAtSkinAndMouthBoundaries() {
        val vertices = tessellate()
        val ringCount = tessellator.verticesPerLip / tessellator.samplesPerLip

        repeat(2) { lipIndex ->
            repeat(tessellator.samplesPerLip) { sampleIndex ->
                val firstRing = lipIndex * tessellator.verticesPerLip + sampleIndex * ringCount
                val lastRing = firstRing + ringCount - 1
                assertEquals(0f, coverage(vertices, firstRing), EPSILON)
                assertEquals(0f, coverage(vertices, lastRing), EPSILON)
            }
        }
    }

    @Test
    fun coreCoverageMatchesReferenceProfiles() {
        val vertices = tessellate()
        val upperCoreVertex = CORE_RING_INDEX
        val lowerCoreVertex = tessellator.verticesPerLip + CORE_RING_INDEX

        assertEquals(
            ReferenceMatteLipstickProfile.upper.effectiveCoreCoverage,
            coverage(vertices, upperCoreVertex),
            EPSILON,
        )
        assertEquals(
            ReferenceMatteLipstickProfile.lower.effectiveCoreCoverage,
            coverage(vertices, lowerCoreVertex),
            EPSILON,
        )
    }

    private fun tessellate(): FloatArray = tessellator.tessellate(
        outerContour = contour(outer = true),
        innerContour = contour(outer = false),
        upperProfile = ReferenceMatteLipstickProfile.upper,
        lowerProfile = ReferenceMatteLipstickProfile.lower,
    )

    private fun contour(outer: Boolean): FloatArray = FloatArray(20 * 2).also { points ->
        repeat(20) { index ->
            val angle = index * (Math.PI * 2.0 / 20.0)
            val radiusX = if (outer) 0.25 else 0.16
            val radiusY = if (outer) 0.10 else 0.045
            points[index * 2] = (0.5 + kotlin.math.cos(angle) * radiusX).toFloat()
            points[index * 2 + 1] = (0.5 + kotlin.math.sin(angle) * radiusY).toFloat()
        }
    }

    private fun coverage(vertices: FloatArray, vertexIndex: Int): Float =
        vertices[vertexIndex * LipMeshTessellator.VERTEX_COMPONENT_COUNT + 2]

    companion object {
        private const val CORE_RING_INDEX = 3
        private const val EPSILON = 0.0001f
    }
}
