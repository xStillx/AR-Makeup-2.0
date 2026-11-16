package com.example.armakeup.arcore

import kotlin.math.sqrt

/** Measured MP surface and camera-space normals, all from one immutable promoted pair. */
internal class HighlighterFaceMesh private constructor(
    val vertices: FloatArray,
    val indices: ShortArray,
    val face: FoundationFaceMesh,
    val cheeks: FloatArray,
    val nose: FloatArray,
    val brows: FloatArray,
    val cupid: FloatArray,
    val referenceDirections: FloatArray,
) {
    companion object {
        const val COMPONENTS = 13 // clip4, local3, view-normal3, view-position3
        const val CHEEK_POINTS = 8

        fun create(surface: BlushFaceMesh, viewModel: FloatArray, face: FoundationFaceMesh): HighlighterFaceMesh? {
            val p = surface.surfacePositions
            if (p.size != 468 * 3 || surface.vertices.size != 468 * 7 ||
                viewModel.size != 16 || viewModel.any { !it.isFinite() }) return null
            fun point(id: Int) = FloatArray(3) { p[id * 3 + it] }
            fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] * (1f - t) + b[it] * t }
            fun blend(a: Int, b: Int, t: Float) = mix(point(a), point(b), t)
            fun unit(v: FloatArray): FloatArray? {
                val length = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                return if (length > 1e-8f && length.isFinite()) FloatArray(3) { v[it] / length } else null
            }
            val normals = Array(468) { FloatArray(3) }
            val neighbours = Array(468) { HashSet<Int>() }
            val ids = surface.indices
            for (t in ids.indices step 3) {
                val a = ids[t].toInt() and 0xffff
                val b = ids[t + 1].toInt() and 0xffff
                val c = ids[t + 2].toInt() and 0xffff
                val u = FloatArray(3) { p[b * 3 + it] - p[a * 3 + it] }
                val v = FloatArray(3) { p[c * 3 + it] - p[a * 3 + it] }
                val n = floatArrayOf(u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
                if (n[2] < 0f) for (axis in 0..2) n[axis] = -n[axis]
                for (i in intArrayOf(a, b, c)) for (axis in 0..2) normals[i][axis] += n[axis]
                neighbours[a].add(b); neighbours[a].add(c)
                neighbours[b].add(a); neighbours[b].add(c)
                neighbours[c].add(a); neighbours[c].add(b)
            }
            var smooth = Array(468) { unit(normals[it]) ?: return null }
            // Smooth the normal field, never the measured positions or depth geometry.
            repeat(2) {
                val previous = smooth
                smooth = Array(468) { i ->
                    val n = FloatArray(3) { axis ->
                        .65f * previous[i][axis] + .35f * neighbours[i].sumOf { previous[it][axis].toDouble() }.toFloat() /
                            neighbours[i].size.coerceAtLeast(1)
                    }
                    unit(n) ?: return null
                }
            }
            val output = FloatArray(468 * COMPONENTS)
            for (i in 0 until 468) {
                val k = i * COMPONENTS
                for (axis in 0..3) output[k + axis] = surface.vertices[i * 7 + axis]
                for (axis in 0..2) output[k + 4 + axis] = p[i * 3 + axis]
                val n = FloatArray(3) { axis ->
                    viewModel[axis] * smooth[i][0] + viewModel[4 + axis] * smooth[i][1] + viewModel[8 + axis] * smooth[i][2]
                }
                val normal = unit(n) ?: return null
                for (axis in 0..2) {
                    output[k + 7 + axis] = normal[axis]
                    output[k + 10 + axis] = viewModel[axis] * p[i * 3] + viewModel[4 + axis] * p[i * 3 + 1] +
                        viewModel[8 + axis] * p[i * 3 + 2] + viewModel[12 + axis]
                }
            }
            val cheeks = FloatArray(CHEEK_POINTS * 2 * 4)
            for ((side, anchors) in arrayOf(
                arrayOf(blend(50, 118, .55f), blend(117, 123, .40f), blend(116, 111, .35f)),
                arrayOf(blend(280, 347, .55f), blend(346, 352, .40f), blend(345, 340, .35f)),
            ).withIndex()) {
                for (i in 0 until CHEEK_POINTS) {
                    val t = i.toFloat() / (CHEEK_POINTS - 1)
                    val a = mix(anchors[0], anchors[1], t)
                    val b = mix(anchors[1], anchors[2], t)
                    val center = mix(a, b, t)
                    val k = (side * CHEEK_POINTS + i) * 4
                    for (axis in 0..2) cheeks[k + axis] = center[axis]
                    cheeks[k + 3] = .001f + .0045f * kotlin.math.sin(Math.PI * t).toFloat()
                }
            }
            fun packed(points: Array<FloatArray>, widths: FloatArray) = FloatArray(points.size * 4) { k ->
                if (k % 4 == 3) widths[k / 4] else points[k / 4][k % 4]
            }
            val nose = packed(arrayOf(blend(6, 197, .5f), point(5), point(1)), floatArrayOf(.0013f, .0018f, .0025f))
            val brows = packed(arrayOf(
                blend(46, 161, .35f), blend(53, 160, .35f), blend(52, 159, .35f),
                blend(276, 388, .35f), blend(283, 387, .35f), blend(282, 386, .35f),
            ), FloatArray(6) { .0017f })
            val up = unit(FloatArray(3) { p[10 * 3 + it] - p[152 * 3 + it] }) ?: return null
            val cupidPoints = arrayOf(point(37), point(0), point(267))
            for (q in cupidPoints) for (axis in 0..2) q[axis] += up[axis] * .0022f
            val cupid = packed(cupidPoints, floatArrayOf(.0012f, .0014f, .0012f))
            val referenceIds = intArrayOf(50, 280, 151, 200)
            val refs = FloatArray(12)
            val centerX = referenceIds.sumOf { output[it * COMPONENTS + 10].toDouble() }.toFloat() / 4f
            val centerY = referenceIds.sumOf { output[it * COMPONENTS + 11].toDouble() }.toFloat() / 4f
            for ((i, id) in referenceIds.withIndex()) {
                refs[i * 3] = ((output[id * COMPONENTS + 10] - centerX) / .06f).coerceIn(-1f, 1f)
                refs[i * 3 + 1] = ((output[id * COMPONENTS + 11] - centerY) / .06f).coerceIn(-1f, 1f)
                refs[i * 3 + 2] = 0f
            }
            return HighlighterFaceMesh(output, ids, face, cheeks, nose, brows, cupid, refs)
        }
    }
}
