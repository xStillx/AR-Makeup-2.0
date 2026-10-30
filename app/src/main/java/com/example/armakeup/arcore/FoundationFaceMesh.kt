package com.example.armakeup.arcore

import kotlin.math.sqrt
import kotlin.math.sin

/** Immutable foundation chart derived only from the promoted, calibrated MP surface. */
internal class FoundationFaceMesh private constructor(
    val vertices: FloatArray,
    val indices: ShortArray,
    val lips: FloatArray,
    val eyes: FloatArray,
    val brows: FloatArray,
    val nostrils: FloatArray,
    val references: FloatArray,
    val sampleRadius: Float,
    val boundary: FloatArray,
    val featureScale: FloatArray,
    val comparisonPlane: FloatArray,
) {
    companion object {
        const val COMPONENTS = 8 // clip xyzw, face-local metres xyz, forehead extension fraction
        private val oval = intArrayOf(10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,
            152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109)
        private val mouth = intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146)
        private val leftEye = intArrayOf(33,246,161,160,159,158,157,173,133,155,154,153,145,144,163,7)
        private val rightEye = intArrayOf(263,466,388,387,386,385,384,398,362,382,381,380,374,373,390,249)
        private val leftBrow = intArrayOf(70,63,105,66,107,55,65,52,53,46)
        private val rightBrow = intArrayOf(300,293,334,296,336,285,295,282,283,276)
        private val foreheadArc = intArrayOf(21,54,103,67,109,10,338,297,332,284,251)

        fun create(surface: BlushFaceMesh, viewportAspect: Float): FoundationFaceMesh? {
            if (surface.surfacePositions.size != 468 * 3) return null
            if (!viewportAspect.isFinite() || viewportAspect <= 0f) return null
            val positions = surface.surfacePositions
            fun distance(a: Int, b: Int): Float = sqrt((0..2).sumOf {
                val d = (positions[a * 3 + it] - positions[b * 3 + it]).toDouble(); d * d
            }).toFloat()
            val width = distance(234, 454)
            if (!width.isFinite() || width !in 0.06f..0.28f) return null
            fun ndc(id: Int, axis: Int): Float {
                val k = id * BlushFaceMesh.COMPONENTS
                return surface.vertices[k + axis] / surface.vertices[k + 3]
            }
            val headX = (ndc(10, 0) - ndc(152, 0)) * viewportAspect
            val headY = ndc(10, 1) - ndc(152, 1)
            val projectedHeight = sqrt(headX * headX + headY * headY)
            if (projectedHeight < 0.03f) return null
            val screenMetres = (distance(10, 152) / projectedHeight).coerceIn(0.015f, 1f)
            val featureScale = floatArrayOf(screenMetres * viewportAspect, screenMetres)
            // Exclusions must follow visible camera features even when a far-side 3D
            // chart overlaps the nose/eye in profile. Depth still resolves the surface.
            fun featureX(id: Int) = ndc(id, 0) * featureScale[0]
            fun featureY(id: Int) = ndc(id, 1) * featureScale[1]
            val output = FloatArray(468 * COMPONENTS)
            // Interpolate positions only. Interpolated, clamped vertex coverage or facing
            // exposes the coarse triangles as patches of different foundation strength.
            for (id in 0 until 468) {
                val src = id * BlushFaceMesh.COMPONENTS; val dst = id * COMPONENTS
                for (axis in 0..3) output[dst + axis] = surface.vertices[src + axis]
                for (axis in 0..2) output[dst + 4 + axis] = positions[id * 3 + axis]
                output[dst + 7] = 0f
            }
            fun contour(ids: IntArray) = FloatArray(ids.size * 2) { k ->
                if (k % 2 == 0) featureX(ids[k / 2]) else featureY(ids[k / 2])
            }
            val eyeContours = FloatArray(16 * 4)
            for (i in 0 until 16) {
                eyeContours[i * 4] = featureX(leftEye[i]); eyeContours[i * 4 + 1] = featureY(leftEye[i])
                eyeContours[i * 4 + 2] = featureX(rightEye[i]); eyeContours[i * 4 + 3] = featureY(rightEye[i])
            }
            val browContours = FloatArray(10 * 4)
            for (i in 0 until 10) {
                browContours[i * 4] = featureX(leftBrow[i]); browContours[i * 4 + 1] = featureY(leftBrow[i])
                browContours[i * 4 + 2] = featureX(rightBrow[i]); browContours[i * 4 + 3] = featureY(rightBrow[i])
            }
            // Conservatively protect the dark openings, not the entire nose.
            val nostrils = floatArrayOf(featureX(98), featureY(98), featureX(327), featureY(327))
            val references = FloatArray(4 * 3)
            for ((i, id) in intArrayOf(50, 280, 151, 200).withIndex()) {
                val k = id * BlushFaceMesh.COMPONENTS; val w = surface.vertices[k + 3]
                val u = surface.vertices[k] / w * 0.5f + 0.5f
                val v = surface.vertices[k + 1] / w * 0.5f + 0.5f
                references[i * 3] = u.coerceIn(0f, 1f)
                references[i * 3 + 1] = v.coerceIn(0f, 1f)
                references[i * 3 + 2] = if (u in 0.02f..0.98f && v in 0.02f..0.98f)
                    ((surface.vertices[k + 6] - 0.35f) / 0.45f).coerceIn(0f, 1f) else 0f
            }
            if (references.filterIndexed { i, _ -> i % 3 == 2 }.sum() < 0.25f) return null
            // Projected face width controls the local lighting scale at any camera distance.
            fun u(id: Int) = surface.vertices[id * 7] / surface.vertices[id * 7 + 3] * 0.5f
            val sampleRadius = (kotlin.math.abs(u(234) - u(454)) * 0.035f).coerceIn(0.003f, 0.025f)
            val coverage = foreheadCoverage(surface, output)
            return FoundationFaceMesh(coverage.vertices, coverage.indices, contour(mouth), eyeContours,
                browContours, nostrils, references, sampleRadius, coverage.boundary, featureScale,
                comparisonPlane(positions, if (ndc(234, 0) < ndc(454, 0)) 234 else 454))
        }

        /** Sagittal plane through this pair's forehead, nose tip and chin, in face-local metres. */
        private fun comparisonPlane(positions: FloatArray, previewLeft: Int): FloatArray {
            fun point(id: Int) = FloatArray(3) { positions[id * 3 + it] }
            val top = point(10); val chin = point(152); val nose = point(1)
            val down = FloatArray(3) { chin[it] - top[it] }
            val forward = FloatArray(3) { nose[it] - top[it] }
            val normal = floatArrayOf(
                down[1] * forward[2] - down[2] * forward[1],
                down[2] * forward[0] - down[0] * forward[2],
                down[0] * forward[1] - down[1] * forward[0],
            )
            val length = sqrt(normal.sumOf { (it * it).toDouble() }).toFloat()
            // Degenerate midline: use ARCore's face-local lateral axis through the nose.
            val origin = if (length.isFinite() && length > 1e-7f) {
                for (axis in 0..2) normal[axis] /= length
                top
            } else {
                normal[0] = 1f; normal[1] = 0f; normal[2] = 0f
                nose
            }
            val side = point(previewLeft)
            if ((0..2).sumOf { (normal[it] * (side[it] - origin[it])).toDouble() } < 0.0)
                for (axis in 0..2) normal[axis] = -normal[axis]
            return floatArrayOf(normal[0], normal[1], normal[2],
                -(normal[0] * origin[0] + normal[1] * origin[1] + normal[2] * origin[2]))
        }

        private data class Coverage(val vertices: FloatArray, val indices: ShortArray, val boundary: FloatArray)

        /** A bounded continuation of this paired forehead, never a screen-space carrier. */
        private fun foreheadCoverage(surface: BlushFaceMesh, original: FloatArray): Coverage {
            val positions = surface.surfacePositions
            val weights = FloatArray(foreheadArc.size) { i ->
                val s = sin(Math.PI * i / (foreheadArc.size - 1)).toFloat(); s * s
            }
            val boundary = FloatArray(36 * 4)
            for (i in oval.indices) {
                for (axis in 0..2) boundary[i * 4 + axis] = positions[oval[i] * 3 + axis]
                val arc = foreheadArc.indexOf(oval[i])
                // Broaden only the upper transition; keep the higher extension and
                // side/jaw feather unchanged so the forehead does not end in a band.
                boundary[i * 4 + 3] = 0.009f + if (arc >= 0) 0.013f * weights[arc] else 0f
            }
            val matrix = surface.projectionMatrix ?: return Coverage(original, surface.indices, boundary)
            val up = FloatArray(3) { positions[10 * 3 + it] - positions[151 * 3 + it] }
            val length = sqrt(up.sumOf { (it * it).toDouble() }).toFloat()
            if (!length.isFinite() || length < 0.002f) return Coverage(original, surface.indices, boundary)
            for (axis in 0..2) up[axis] /= length
            val expanded = original.copyOf((468 + foreheadArc.size - 2) * COMPONENTS)
            val expandedBoundary = boundary.copyOf()
            val outer = IntArray(foreheadArc.size) { foreheadArc[it] }
            for (i in 1 until foreheadArc.lastIndex) {
                val id = 468 + i - 1; val base = id * COMPONENTS
                val source = foreheadArc[i] * 3
                for (axis in 0..2) expanded[base + 4 + axis] = positions[source + axis] +
                    up[axis] * (0.028f * weights[i])
                for (axis in 0..3) expanded[base + axis] = matrix[axis] * expanded[base + 4] +
                    matrix[4 + axis] * expanded[base + 5] + matrix[8 + axis] * expanded[base + 6] + matrix[12 + axis]
                if ((0..7).any { !expanded[base + it].isFinite() } || expanded[base + 3] <= 0f)
                    return Coverage(original, surface.indices, boundary)
                expanded[base + 7] = 1f
                outer[i] = id
                val boundaryIndex = oval.indexOf(foreheadArc[i])
                for (axis in 0..2) expandedBoundary[boundaryIndex * 4 + axis] = expanded[base + 4 + axis]
            }
            val extra = ArrayList<Short>(54)
            for (i in 0 until foreheadArc.lastIndex) {
                val a = foreheadArc[i]; val b = foreheadArc[i + 1]
                if (b != outer[i + 1]) extra.addAll(listOf(a.toShort(), b.toShort(), outer[i + 1].toShort()))
                if (a != outer[i]) extra.addAll(listOf(a.toShort(), outer[i + 1].toShort(), outer[i].toShort()))
            }
            return Coverage(expanded, surface.indices + extra.toShortArray(), expandedBoundary)
        }
    }
}
