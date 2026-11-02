package com.example.armakeup.arcore

import kotlin.math.sqrt

/** Smooth user-drawn under-eye boundary on the same immutable calibrated surface. */
internal class ConcealerFaceMesh private constructor(
    val face: FoundationFaceMesh,
    val charts: FloatArray,
    val contours: FloatArray,
    val skinReferences: FloatArray,
) {
    companion object {
        private const val CONTROL_POINTS = ConcealerContourAnchors.COUNT
        private const val SAMPLES_PER_SPAN = 2
        const val CONTOUR_POINTS = CONTROL_POINTS * SAMPLES_PER_SPAN
        private val eyeCorners = arrayOf(intArrayOf(133,33,145),intArrayOf(362,263,374))
        private val cheekReferences = arrayOf(intArrayOf(101,50,123),intArrayOf(330,280,352))

        fun create(surface: BlushFaceMesh, aspect: Float, pairedFace: FoundationFaceMesh? = null): ConcealerFaceMesh? {
            val face = pairedFace ?: FoundationFaceMesh.create(surface,aspect) ?: return null
            val positions = surface.surfacePositions
            fun point(id: Int) = FloatArray(3) { positions[id*3+it] }
            fun dot(a: FloatArray,b: FloatArray) = a.indices.sumOf { (a[it]*b[it]).toDouble() }.toFloat()
            fun normalized(a: FloatArray): FloatArray? {
                val length = sqrt(dot(a,a))
                if (!length.isFinite() || length < 0.001f) return null
                return FloatArray(3) { a[it]/length }
            }
            val charts = FloatArray(12)
            val contours = FloatArray(CONTOUR_POINTS*4)
            val references = FloatArray(18)
            for (side in 0..1) {
                val ids = eyeCorners[side]
                val inner = point(ids[0]); val outer = point(ids[1])
                val lateral = FloatArray(3) { outer[it]-inner[it] }
                val width = sqrt(dot(lateral,lateral))
                if (width !in 0.012f..0.075f) return null
                val x = normalized(lateral) ?: return null
                val low = point(ids[2]); val cheek = point(cheekReferences[side][1])
                val down = FloatArray(3) { cheek[it]-low[it] }
                val lateralDown = dot(x,down)
                val y = normalized(FloatArray(3) { down[it]-x[it]*lateralDown }) ?: return null
                x.copyInto(charts,side*6); y.copyInto(charts,side*6+3)
                val boundary = FloatArray(CONTROL_POINTS*2)
                for (i in 0 until CONTROL_POINTS) {
                    val p = FloatArray(3)
                    for (j in 0..2) {
                        val id = ConcealerContourAnchors.triangles[side][i*3+j]
                        val weight = ConcealerContourAnchors.weights[i*3+j]
                        for (k in 0..2) p[k] += positions[id*3+k]*weight
                    }
                    boundary[i*2] = dot(p,x)
                    boundary[i*2+1] = dot(p,y)
                }
                // A closed centripetal Hermite curve passes through every mapped
                // anchor. Shared tangents round the wings and lower cap without
                // replacing the outline by a new triangle or shifting its tip.
                val spans = FloatArray(CONTROL_POINTS)
                val tangents = FloatArray(CONTROL_POINTS*2)
                for (i in 0 until CONTROL_POINTS) {
                    val next = (i+1)%CONTROL_POINTS
                    val dx = boundary[next*2]-boundary[i*2]
                    val dy = boundary[next*2+1]-boundary[i*2+1]
                    val length = sqrt(dx*dx+dy*dy)
                    if (!length.isFinite() || length < 0.000001f) return null
                    spans[i] = sqrt(length)
                }
                for (i in 0 until CONTROL_POINTS) {
                    val previous = (i+CONTROL_POINTS-1)%CONTROL_POINTS
                    val next = (i+1)%CONTROL_POINTS
                    val before = spans[previous]; val after = spans[i]
                    for (k in 0..1) {
                        val incoming = boundary[i*2+k]-boundary[previous*2+k]
                        val outgoing = boundary[next*2+k]-boundary[i*2+k]
                        tangents[i*2+k] = 0.75f*(incoming/before-
                            (incoming+outgoing)/(before+after)+outgoing/after)
                    }
                }
                for (i in 0 until CONTROL_POINTS) {
                    val next = (i+1)%CONTROL_POINTS
                    for (sample in 0 until SAMPLES_PER_SPAN) {
                        val t = sample.toFloat()/SAMPLES_PER_SPAN
                        val t2 = t*t; val t3 = t2*t
                        val start = 2f*t3-3f*t2+1f; val end = -2f*t3+3f*t2
                        val startTangent = (t3-2f*t2+t)*spans[i]
                        val endTangent = (t3-t2)*spans[i]
                        val output = (i*SAMPLES_PER_SPAN+sample)*4+side*2
                        for (k in 0..1) contours[output+k] =
                            start*boundary[i*2+k]+end*boundary[next*2+k]+
                                startTangent*tangents[i*2+k]+endTangent*tangents[next*2+k]
                    }
                }
                for ((i,id) in cheekReferences[side].withIndex()) {
                    val k = id*BlushFaceMesh.COMPONENTS
                    val u = surface.vertices[k]/surface.vertices[k+3]*0.5f+0.5f
                    val v = surface.vertices[k+1]/surface.vertices[k+3]*0.5f+0.5f
                    val j = (side*3+i)*3
                    references[j] = u.coerceIn(0f,1f); references[j+1] = v.coerceIn(0f,1f)
                    references[j+2] = if (u in 0.02f..0.98f && v in 0.02f..0.98f)
                        ((surface.vertices[k+6]-0.35f)/0.45f).coerceIn(0f,1f) else 0f
                }
            }
            if (charts.any { !it.isFinite() } || contours.any { !it.isFinite() }) return null
            return ConcealerFaceMesh(face,charts,contours,references)
        }
    }
}
