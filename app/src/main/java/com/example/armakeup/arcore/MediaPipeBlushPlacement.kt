package com.example.armakeup.arcore

import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.NormalizedImageTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Closed cheek boundaries from paired landmarks and the captured face surface. */
internal object MediaPipeBlushPlacement {
    var lastDiagnostics = "not_placed"
        private set

    private data class Anchor(val indices: IntArray, val weights: FloatArray)
    private fun anchor(a: Int, b: Int = a, weightB: Float = 0f) =
        Anchor(intArrayOf(a, b), floatArrayOf(1f - weightB, weightB))

    // Measured base controls; the body is shaped around the cheek apple below.
    // Keep the accepted outer wing attached to the same 3D surface.
    // Point numbers on the user's latest sketch are illustrative, not target IDs.
    private val anchors = arrayOf(
        Anchor(intArrayOf(162, 127, 34), floatArrayOf(0.28f, 0.52f, 0.20f)),
        anchor(35, 111, 0.95f), anchor(31, 111, 0.875f),
        anchor(229, 118, 0.225f), anchor(230, 119, 0.075f), anchor(120),
        anchor(101), anchor(118, 50, 0.55f), anchor(117, 50, 0.625f),
        anchor(116, 123, 0.50f), anchor(234, 227, 0.20f), anchor(127, 34, 0.20f),
    )
    private val mirrors = mapOf(
        33 to 263, 133 to 362,
        162 to 389, 34 to 264, 127 to 356, 35 to 265, 31 to 261, 111 to 340,
        229 to 449, 118 to 347, 230 to 450, 119 to 348, 231 to 451,
        120 to 349, 101 to 330, 117 to 346, 50 to 280, 116 to 345,
        123 to 352, 227 to 447, 234 to 454,
    )

    fun apply(
        mesh: BlushFaceMesh,
        coordinates: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        viewportWidth: Int,
        viewportHeight: Int,
    ): BlushFaceMesh? {
        lastDiagnostics = "invalid_input"
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0 ||
            rotationDegrees % 90 != 0 || coordinates.size < (454 + 1) * 3) return null
        // Keep the accepted temple shaping. Retract only the three outer
        // temple controls smoothly during turns, using this paired surface's pose.
        // No visibility switch or geometry history: the same pose gives the same shape.
        val pose = mesh.projectionMatrix
        val horizontal = if (pose != null) sqrt(pose[3] * pose[3] + pose[11] * pose[11]) else 0f
        val sineYaw = if (pose != null && horizontal > 0.000001f) abs(pose[3]) / horizontal else 0f
        val turn = ((sineYaw - 0.17f) / 0.50f).coerceIn(0f, 1f)
        val templeTurn = turn * turn * turn * (10f + turn * (6f * turn - 15f))
        // Keep the 20% frontal inset. Two more equal 10-point steps from 95%
        // move the turned endpoint slightly beyond the temple guide in chart space.
        val extraTempleInset = templeTurn * (1.15f - 0.20f) / (1f - 0.20f)
        val image = NormalizedImageTransform(rotationDegrees, mirrorHorizontal)
        val fill = FillCenterTransform.calculate(viewportWidth, viewportHeight, sourceWidth, sourceHeight)
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        for (i in mesh.surfacePositions.indices step 3) {
            minX = minOf(minX, mesh.surfacePositions[i])
            maxX = maxOf(maxX, mesh.surfacePositions[i])
            minZ = minOf(minZ, mesh.surfacePositions[i + 2])
        }
        val radius = (maxX - minX) * 0.5f
        if (!radius.isFinite() || radius < 0.01f) return null
        val midX = (maxX + minX) * 0.5f
        val originZ = minZ - radius * 0.24f
        fun chart(point: SurfacePoint) = floatArrayOf(
            atan2(point.x - midX, point.z - originZ) * radius, point.y)
        fun screen(index: Int): FloatArray? {
            val x = coordinates[index * 3]
            val y = coordinates[index * 3 + 1]
            if (!x.isFinite() || !y.isFinite() || x !in -0.2f..1.2f || y !in -0.2f..1.2f) return null
            return floatArrayOf(fill.mapX(image.mapX(x, y), sourceWidth) / viewportWidth * 2f - 1f,
                1f - fill.mapY(image.mapY(x, y), sourceHeight) / viewportHeight * 2f)
        }
        val packed = FloatArray(BlushFaceMesh.CONTOUR_POINTS * 4)
        // Feather/visibility, then the two UV bounds for cheap fragment rejection.
        val parameters = FloatArray(12)
        val diagnostics = ArrayList<String>(2)
        for (sideIndex in 0..1) {
            fun id(index: Int) = if (sideIndex == 0) index else mirrors.getValue(index)
            val groupId = id(117)
            var centerId = groupId
            var centerScreen: FloatArray? = null
            var center: SurfacePoint? = null
            for (candidate in intArrayOf(117, 116, 111)) {
                val position = screen(id(candidate)) ?: continue
                val hit = lift(mesh, position[0], position[1]) ?: continue
                if (abs(hit.side) < 0.2f) continue
                centerId = id(candidate)
                centerScreen = position
                center = hit
                break
            }
            if (center == null || centerScreen == null) {
                diagnostics.add("$groupId:missing_center")
                continue
            }
            val visibleCenter = center
            val visibleCenterScreen = centerScreen
            val slot = if (visibleCenter.side < 0f) 0 else 1
            if (parameters[slot + 2] > 0f) {
                diagnostics.add("$groupId:occluded_side")
                continue
            }
            val raw = ArrayList<FloatArray>(anchors.size)
            var missingInput = false
            var clipped = 0
            val lifted = HashMap<Int, SurfacePoint>()
            lifted[centerId] = visibleCenter
            fun measured(index: Int): SurfacePoint? {
                val actualId = id(index)
                lifted[actualId]?.let { return it }
                val target = screen(actualId) ?: return null
                var hit = lift(mesh, target[0], target[1])?.takeIf { it.side * visibleCenter.side > 0f }
                if (hit == null) {
                    clipped++
                    if (clipped > 6) return null
                    var low = 0f
                    var high = 1f
                    hit = visibleCenter
                    repeat(5) {
                        val t = (low + high) * 0.5f
                        val candidate = lift(mesh, visibleCenterScreen[0] + (target[0] - visibleCenterScreen[0]) * t,
                            visibleCenterScreen[1] + (target[1] - visibleCenterScreen[1]) * t)
                        if (candidate != null && candidate.side * visibleCenter.side > 0f) {
                            low = t
                            hit = candidate
                        } else high = t
                    }
                }
                return hit!!.also { lifted[actualId] = it }
            }
            for ((anchorIndex, anchor) in anchors.withIndex()) {
                var u = 0f
                var v = 0f
                for (j in anchor.indices.indices) {
                    if (anchor.weights[j] == 0f) continue
                    val point = measured(anchor.indices[j])
                    if (point == null) { missingInput = true; break }
                    val uv = chart(point)
                    u += uv[0] * anchor.weights[j]
                    v += uv[1] * anchor.weights[j]
                }
                if (missingInput) break
                if (extraTempleInset > 0f && (anchorIndex == 0 || anchorIndex >= anchors.size - 2)) {
                    val temple = measured(if (anchorIndex == anchors.size - 2) 227 else 34)
                    if (temple == null) { missingInput = true; break }
                    val uv = chart(temple)
                    u += (uv[0] - u) * extraTempleInset
                    v += (uv[1] - v) * extraTempleInset
                }
                // Interpolate after the 3D lift, so the control point remains attached
                // to the same skin surface when perspective and head angle change.
                raw.add(floatArrayOf(u, v))
            }
            if (missingInput || raw.size != anchors.size || clipped > 6) {
                diagnostics.add("$centerId:invalid_boundary_$clipped")
                continue
            }
            // Start on the cheek apple beneath mid-eye. Eye corners are optional
            // guides, so an occluded eye never invalidates an otherwise valid cheek.
            val apple = chart(lifted.getValue(id(101)))
            val underEye = chart(lifted.getValue(id(230)))
            fun eyeCorner(index: Int): FloatArray? {
                val target = screen(id(index)) ?: return null
                val hit = lift(mesh, target[0], target[1]) ?: return null
                return if (hit.side * visibleCenter.side > 0f) chart(hit) else null
            }
            val outerEye = eyeCorner(33)
            val innerEye = eyeCorner(133)
            val midEyeU = if (outerEye != null && innerEye != null)
                (outerEye[0] + innerEye[0]) * 0.5f else underEye[0]
            val sign = if (visibleCenter.side < 0f) -1f else 1f
            val startU = abs(midEyeU)
            for (i in 1..9) raw[i][0] = sign * maxOf(abs(raw[i][0]), startU)
            raw[3][1] = minOf(raw[3][1] - 0.002f, apple[1] + 0.006f)
            raw[4] = floatArrayOf(sign * (startU + 0.0015f), apple[1] + 0.004f)
            raw[5] = floatArrayOf(sign * startU, apple[1])
            raw[6] = floatArrayOf(sign * (startU + 0.0015f), apple[1] - 0.004f)
            // Widen downward locally, tapering before the accepted temple controls.
            raw[7][1] -= 0.004f
            raw[8][1] -= 0.0035f
            raw[9][1] -= 0.0015f
            // Keep feather calibrated to the untrimmed apple shape from THIS pair.
            // Shortening the inner tip must not accidentally sharpen the whole cheek.
            val fullBody = raw.map { it.copyOf() }.toMutableList()
            var featherBoundary = roundedBoundary(fullBody)
            if (!simple(featherBoundary) && untangle(fullBody) >= 0)
                featherBoundary = roundedBoundary(fullBody)
            val preservedFeather = (minOf(
                featherBoundary.maxOf { it[0] } - featherBoundary.minOf { it[0] },
                featherBoundary.maxOf { it[1] } - featherBoundary.minOf { it[1] },
            ) * 0.22f).coerceIn(0.003f, 0.006f)
            // Trim only the nose-side start by 4 mm, along the measured cheek chart.
            val shiftedStartU = startU + 0.004f
            for (i in 1..9) raw[i][0] = sign * maxOf(abs(raw[i][0]), shiftedStartU)
            raw[4][0] = sign * (shiftedStartU + 0.0015f)
            raw[5][0] = sign * shiftedStartU
            raw[6][0] = sign * (shiftedStartU + 0.0015f)
            var polygon = roundedBoundary(raw)
            var untangled = 0
            if (!simple(polygon)) {
                untangled = untangle(raw)
                if (untangled < 0) {
                    diagnostics.add("$centerId:crossed_boundary")
                    continue
                }
                polygon = roundedBoundary(raw)
            }
            var area = 0f
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                area += a[0] * b[1] - b[0] * a[1]
            }
            val width = polygon.maxOf { it[0] } - polygon.minOf { it[0] }
            val height = polygon.maxOf { it[1] } - polygon.minOf { it[1] }
            if (width !in 0.015f..0.16f || height !in 0.006f..0.08f || abs(area) < 0.0001f ||
                polygon.any { it[0] * visibleCenter.side <= 0f } || !simple(polygon)) {
                diagnostics.add("$centerId:degenerate_boundary")
                continue
            }
            for (i in polygon.indices) {
                packed[i * 4 + slot * 2] = polygon[i][0]
                packed[i * 4 + slot * 2 + 1] = polygon[i][1]
            }
            parameters[slot] = preservedFeather
            parameters[slot + 2] = 1f
            val bounds = 4 + slot * 4
            parameters[bounds] = polygon.minOf { it[0] }
            parameters[bounds + 1] = polygon.minOf { it[1] }
            parameters[bounds + 2] = polygon.maxOf { it[0] }
            parameters[bounds + 3] = polygon.maxOf { it[1] }
            val repair = if (untangled > 0) "_untangled_$untangled" else ""
            diagnostics.add("$groupId:ok${if (clipped > 0) "_clipped_$clipped" else ""}$repair")
        }
        lastDiagnostics = diagnostics.joinToString(",") + ";templeTurn=$templeTurn"
        if (parameters[2] == 0f && parameters[3] == 0f) return null
        val q = FloatArray(mesh.vertices.size / BlushFaceMesh.COMPONENTS * 2)
        for (i in q.indices step 2) {
            val source = i / 2 * 3
            q[i] = atan2(mesh.surfacePositions[source] - midX, mesh.surfacePositions[source + 2] - originZ) * radius
            q[i + 1] = mesh.surfacePositions[source + 1]
        }
        return mesh.withCheekCoordinates(q, packed, parameters)
    }

    private fun simple(polygon: List<FloatArray>) = firstCrossing(polygon) == null

    /** One corner-cutting pass without overshoot, retaining the fixed uniform size. */
    private fun roundedBoundary(raw: List<FloatArray>): ArrayList<FloatArray> {
        val polygon = ArrayList<FloatArray>(BlushFaceMesh.CONTOUR_POINTS)
        for (i in raw.indices) {
            val a = raw[i]
            val b = raw[(i + 1) % raw.size]
            polygon.add(floatArrayOf(a[0] * 0.75f + b[0] * 0.25f, a[1] * 0.75f + b[1] * 0.25f))
            polygon.add(floatArrayOf(a[0] * 0.25f + b[0] * 0.75f, a[1] * 0.25f + b[1] * 0.75f))
        }
        return polygon
    }

    /** Bounded 2-opt: each swap shortens crossed edges and preserves every measured point. */
    private fun untangle(polygon: MutableList<FloatArray>): Int {
        repeat(polygon.size) { corrections ->
            val crossing = firstCrossing(polygon) ?: return corrections
            polygon.subList(crossing.first + 1, crossing.second + 1).reverse()
        }
        return if (simple(polygon)) polygon.size else -1
    }

    private fun firstCrossing(polygon: List<FloatArray>): Pair<Int, Int>? {
        fun cross(a: FloatArray, b: FloatArray, c: FloatArray) =
            (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        for (i in polygon.indices) for (j in i + 2 until polygon.size) {
            if (i == 0 && j == polygon.lastIndex) continue
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val c = polygon[j]
            val d = polygon[(j + 1) % polygon.size]
            if (cross(a, b, c) * cross(a, b, d) < -1e-12f &&
                cross(c, d, a) * cross(c, d, b) < -1e-12f) return i to j
        }
        return null
    }

    internal data class SurfacePoint(val x: Float, val y: Float, val z: Float, val side: Float, val facing: Float)

    /** Screen barycentrics with perspective-correct attributes and nearest visible depth. */
    internal fun lift(mesh: BlushFaceMesh, x: Float, y: Float): SurfacePoint? {
        val vertices = mesh.vertices
        var nearestDepth = Float.POSITIVE_INFINITY
        var localX = 0f
        var localY = 0f
        var localZ = 0f
        var side = 0f
        var cameraFacing = 0f
        val surface = mesh.surfacePositions
        for (triangle in mesh.indices.indices step 3) {
            val a = (mesh.indices[triangle].toInt() and 0xffff) * BlushFaceMesh.COMPONENTS
            val b = (mesh.indices[triangle + 1].toInt() and 0xffff) * BlushFaceMesh.COMPONENTS
            val c = (mesh.indices[triangle + 2].toInt() and 0xffff) * BlushFaceMesh.COMPONENTS
            val wa = 1f / vertices[a + 3]
            val wb = 1f / vertices[b + 3]
            val wc = 1f / vertices[c + 3]
            val ax = vertices[a] * wa
            val ay = vertices[a + 1] * wa
            val bx = vertices[b] * wb
            val by = vertices[b + 1] * wb
            val cx = vertices[c] * wc
            val cy = vertices[c + 1] * wc
            val determinant = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(determinant) < 1e-8f) continue
            val u = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / determinant
            val v = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / determinant
            val w = 1f - u - v
            if (minOf(u, v, w) < -1e-4f) continue
            val depth = u * vertices[a + 2] * wa + v * vertices[b + 2] * wb + w * vertices[c + 2] * wc
            if (depth >= nearestDepth) continue
            val denominator = u * wa + v * wb + w * wc
            if (denominator <= 0f) continue
            val facing = (u * vertices[a + 6] * wa + v * vertices[b + 6] * wb +
                w * vertices[c + 6] * wc) / denominator
            // Do not look through a nearer back-facing/edge-on surface for a different cheek.
            nearestDepth = depth
            cameraFacing = facing
            fun attribute(axis: Int): Float = (
                u * surface[a / BlushFaceMesh.COMPONENTS * 3 + axis] * wa +
                v * surface[b / BlushFaceMesh.COMPONENTS * 3 + axis] * wb +
                w * surface[c / BlushFaceMesh.COMPONENTS * 3 + axis] * wc) / denominator
            localX = if (facing > 0.08f) attribute(0) else Float.NaN
            localY = attribute(1)
            localZ = attribute(2)
            side = (u * vertices[a + 4] * wa + v * vertices[b + 4] * wb +
                w * vertices[c + 4] * wc) / denominator
        }
        return if (nearestDepth.isFinite() && localX.isFinite() && localY.isFinite() && localZ.isFinite())
            SurfacePoint(localX, localY, localZ, side, cameraFacing) else null
    }
}
