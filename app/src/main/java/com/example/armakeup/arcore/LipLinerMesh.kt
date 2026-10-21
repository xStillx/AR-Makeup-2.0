package com.example.armakeup.arcore

import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import kotlin.math.abs
import kotlin.math.hypot

/** Lip-side strip anchored to the shared coverage edge, without lipstick's raster carriers. */
internal class LipLinerMesh(private val tessellator: LipMeshTessellator) {
    // Clip x/y, normal distance and lip depth in reference texels, ring, arc.
    val vertices = FloatArray(tessellator.vertexCount * COMPONENTS)
    var antialias = 1f
        private set

    fun update(outer: FloatArray, inner: FloatArray, width: Int, height: Int,
        lowerOuterOffsetsPixels: FloatArray? = null): Boolean {
        if (width <= 0 || height <= 0 || outer.size != 40 || inner.size != 40 ||
            outer.any { !it.isFinite() } || inner.any { !it.isFinite() }) return false
        if (lowerOuterOffsetsPixels != null &&
            (lowerOuterOffsetsPixels.size != tessellator.samplesPerLip * 2 ||
                lowerOuterOffsetsPixels.any { !it.isFinite() })) return false
        val axisX = (outer[20] - outer[0]) * width
        val axisY = (outer[21] - outer[1]) * height
        val mouthWidth = hypot(axisX, axisY)
        if (mouthWidth < 2f) return false
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        for (point in 0 until 20) {
            val across = (-outer[point * 2] * width * axisY +
                outer[point * 2 + 1] * height * axisX) / mouthWidth
            minimum = minOf(minimum, across)
            maximum = maxOf(maximum, across)
        }
        val lipHeight = maximum - minimum
        if (lipHeight < 2f) return false
        val scale = 112f / lipHeight
        antialias = scale.coerceIn(0.35f, 3f)
        val surface = tessellator.tessellate(outer, inner,
            ReferenceMatteLipstickProfile.upper, ReferenceMatteLipstickProfile.lower)
        val rings = tessellator.verticesPerLip / tessellator.samplesPerLip
        for (lip in 0..1) {
            val lipStart = lip * tessellator.verticesPerLip
            for (sample in 0 until tessellator.samplesPerLip) {
                val start = lipStart + sample * rings
                val outerOffset = start * LipMeshTessellator.VERTEX_COMPONENT_COUNT
                val innerOffset = (start + rings - 1) * LipMeshTessellator.VERTEX_COMPONENT_COUNT
                val previous = (lipStart + (sample - 1).coerceAtLeast(0) * rings) * LipMeshTessellator.VERTEX_COMPONENT_COUNT
                val next = (lipStart + (sample + 1).coerceAtMost(tessellator.samplesPerLip - 1) * rings) * LipMeshTessellator.VERTEX_COMPONENT_COUNT
                val offsets = if (lip == 1) lowerOuterOffsetsPixels else null
                val offsetX = offsets?.get(sample * 2) ?: 0f
                val offsetY = offsets?.get(sample * 2 + 1) ?: 0f
                val previousPoint = (sample - 1).coerceAtLeast(0) * 2
                val nextPoint = (sample + 1).coerceAtMost(tessellator.samplesPerLip - 1) * 2
                val tangentX = (surface[next] - surface[previous]) * width +
                    (offsets?.get(nextPoint) ?: 0f) - (offsets?.get(previousPoint) ?: 0f)
                val tangentY = (surface[next + 1] - surface[previous + 1]) * height +
                    (offsets?.get(nextPoint + 1) ?: 0f) - (offsets?.get(previousPoint + 1) ?: 0f)
                val acrossX = (surface[innerOffset] - surface[outerOffset]) * width - offsetX
                val acrossY = (surface[innerOffset + 1] - surface[outerOffset + 1]) * height - offsetY
                val thickness = abs(tangentX * acrossY - tangentY * acrossX) /
                    hypot(tangentX, tangentY).coerceAtLeast(0.001f)
                for (ring in 0 until rings) {
                    val source = (start + ring) * LipMeshTessellator.VERTEX_COMPONENT_COUNT
                    val output = (start + ring) * COMPONENTS
                    val fraction = surface[source + LipMeshTessellator.RING_COMPONENT_OFFSET]
                    val arc = surface[source + LipMeshTessellator.ARC_COMPONENT_OFFSET]
                    vertices[output] = (surface[source] + offsetX * (1f - fraction) / width) * 2f - 1f
                    vertices[output + 1] = 1f - (surface[source + 1] + offsetY * (1f - fraction) / height) * 2f
                    vertices[output + 2] = fraction * thickness * scale
                    vertices[output + 3] = thickness * scale
                    vertices[output + 4] = fraction
                    vertices[output + 5] = arc
                }
            }
        }
        return vertices.all { it.isFinite() }
    }

    companion object { const val COMPONENTS = 6 }
}
