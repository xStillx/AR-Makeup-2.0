package com.example.armakeup.arcore

import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.NormalizedImageTransform
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Connectivity already shipped in the pinned MediaPipe SDK; no new model or mesh asset. */
internal object MediaPipeBlushTopology {
    val indices: ShortArray by lazy {
        val neighbours = Array(468) { HashSet<Int>() }
        for (edge in FaceLandmarker.FACE_LANDMARKS_TESSELATION) {
            val a = edge.start()
            val b = edge.end()
            if (a !in neighbours.indices || b !in neighbours.indices) continue
            neighbours[a].add(b)
            neighbours[b].add(a)
        }
        val faces = ArrayList<IntArray>()
        for (a in neighbours.indices) for (b in neighbours[a].sorted()) {
            if (b <= a) continue
            for (c in neighbours[a].sorted()) {
                if (c > b && c in neighbours[b]) faces.add(intArrayOf(a, b, c))
            }
        }
        fun key(a: Int, b: Int) = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
        val incidence = HashMap<Long, Int>()
        for (face in faces) for (i in 0..2) {
            val edge = key(face[i], face[(i + 1) % 3])
            incidence[edge] = (incidence[edge] ?: 0) + 1
        }
        // A separating three-cycle encloses other triangles; it is not a face.
        val surface = faces.filterNot { face ->
            (0..2).all { incidence.getValue(key(face[it], face[(it + 1) % 3])) > 2 }
        }
        ShortArray(surface.size * 3) { surface[it / 3][it % 3].toShort() }
    }
}

/** Continuous measured 3D surface. ARCore supplies metric calibration, not its raster boundary. */
internal object MediaPipeBlushSurface {
    var lastDiagnostics = "not_built"
        private set
    private val calibrationIds = intArrayOf(
        1, 4, 33, 133, 263, 362, 50, 101, 116, 117, 118, 187,
        280, 330, 345, 346, 347, 411, 61, 291,
    )

    fun create(raw: BlushFaceMesh, coordinates: FloatArray,
        sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int, mirrorHorizontal: Boolean,
        viewportWidth: Int, viewportHeight: Int, triangles: ShortArray): BlushFaceMesh? {
        lastDiagnostics = "invalid_input"
        val m = raw.projectionMatrix ?: return null
        if (coordinates.size < 468 * 3 || sourceWidth <= 0 || sourceHeight <= 0 ||
            viewportWidth <= 0 || viewportHeight <= 0 || rotationDegrees % 90 != 0 ||
            (0 until 468 * 3).any { !coordinates[it].isFinite() }) return null
        val transform = NormalizedImageTransform(rotationDegrees, mirrorHorizontal)
        val fill = FillCenterTransform.calculate(viewportWidth, viewportHeight, sourceWidth, sourceHeight)
        val screen = FloatArray(468 * 2)
        for (i in 0 until 468) {
            val x = coordinates[i * 3]
            val y = coordinates[i * 3 + 1]
            screen[i * 2] = fill.mapX(transform.mapX(x, y), sourceWidth) / viewportWidth * 2f - 1f
            screen[i * 2 + 1] = 1f - fill.mapY(transform.mapY(x, y), sourceHeight) / viewportHeight * 2f
        }
        val zs = ArrayList<Double>()
        val depths = ArrayList<Double>()
        for (i in calibrationIds) {
            val p = MediaPipeBlushPlacement.lift(raw, screen[i * 2], screen[i * 2 + 1]) ?: continue
            if (p.facing < 0.25f) continue
            val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
            if (w > 0f) {
                zs.add(coordinates[i * 3 + 2].toDouble())
                depths.add(w.toDouble())
            }
        }
        if (zs.size < 6) { lastDiagnostics = "few_calibration_points"; return null }
        var keep = BooleanArray(zs.size) { true }
        var fit: DoubleArray? = null
        repeat(3) {
            fit = regression(zs.toDoubleArray(), depths.toDoubleArray(), keep)
            val line = fit ?: return null
            val errors = DoubleArray(zs.size) { abs(depths[it] - line[0] * zs[it] - line[1]) }
            val sorted = errors.sorted()
            val threshold = max(0.003, sorted[sorted.size / 2] * 2.5)
            val next = BooleanArray(zs.size) { errors[it] <= threshold }
            if (next.count { it } >= 6) keep = next
        }
        val line = fit ?: return null
        if (line[0] <= 0.0 || !line.all { it.isFinite() }) {
            lastDiagnostics = "invalid_depth_fit"
            return null
        }
        val rawCount = raw.vertices.size / BlushFaceMesh.COMPONENTS
        val ws = DoubleArray(rawCount) { raw.vertices[it * 7 + 3].toDouble() }
        val clipZs = DoubleArray(rawCount) { raw.vertices[it * 7 + 2].toDouble() }
        val clipDepth = regression(ws, clipZs, BooleanArray(rawCount) { true }) ?: return null
        val inverse = inverse3(doubleArrayOf(
            m[0].toDouble(), m[4].toDouble(), m[8].toDouble(),
            m[1].toDouble(), m[5].toDouble(), m[9].toDouble(),
            m[3].toDouble(), m[7].toDouble(), m[11].toDouble(),
        )) ?: return null
        val vertices = FloatArray(468 * 7)
        val positions = FloatArray(468 * 3)
        val low = max(0.01, ws.min() - 0.05)
        val high = ws.max() + 0.05
        for (i in 0 until 468) {
            val w = (line[0] * coordinates[i * 3 + 2] + line[1]).coerceIn(low, high)
            val x = screen[i * 2] * w
            val y = screen[i * 2 + 1] * w
            vertices[i * 7] = x.toFloat()
            vertices[i * 7 + 1] = y.toFloat()
            vertices[i * 7 + 2] = (clipDepth[0] * w + clipDepth[1]).toFloat()
            vertices[i * 7 + 3] = w.toFloat()
            val bx = x - m[12]
            val by = y - m[13]
            val bw = w - m[15]
            for (axis in 0..2) positions[i * 3 + axis] =
                (inverse[axis * 3] * bx + inverse[axis * 3 + 1] * by + inverse[axis * 3 + 2] * bw).toFloat()
        }
        val normals = DoubleArray(468 * 3)
        for (t in triangles.indices step 3) {
            val a = (triangles[t].toInt() and 0xffff) * 3
            val b = (triangles[t + 1].toInt() and 0xffff) * 3
            val c = (triangles[t + 2].toInt() and 0xffff) * 3
            val ux = (positions[b] - positions[a]).toDouble()
            val uy = (positions[b + 1] - positions[a + 1]).toDouble()
            val uz = (positions[b + 2] - positions[a + 2]).toDouble()
            val vx = (positions[c] - positions[a]).toDouble()
            val vy = (positions[c + 1] - positions[a + 1]).toDouble()
            val vz = (positions[c + 2] - positions[a + 2]).toDouble()
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            if (nz < 0.0) { nx = -nx; ny = -ny; nz = -nz }
            for (point in intArrayOf(a, b, c)) {
                normals[point] += nx; normals[point + 1] += ny; normals[point + 2] += nz
            }
        }
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 468) {
            minX = minOf(minX, positions[i * 3]); maxX = maxOf(maxX, positions[i * 3])
            minY = minOf(minY, positions[i * 3 + 1]); maxY = maxOf(maxY, positions[i * 3 + 1])
        }
        if (maxX - minX < 0.02f || maxY - minY < 0.02f) return null
        val cameraLength = sqrt((m[3] * m[3] + m[7] * m[7] + m[11] * m[11]).toDouble())
        if (cameraLength < 1e-9) return null
        for (i in 0 until 468) {
            vertices[i * 7 + 4] = (positions[i * 3] - (minX + maxX) * 0.5f) / ((maxX - minX) * 0.5f)
            vertices[i * 7 + 5] = (positions[i * 3 + 1] - minY) / (maxY - minY)
            val n = i * 3
            val length = sqrt(normals[n] * normals[n] + normals[n + 1] * normals[n + 1] + normals[n + 2] * normals[n + 2])
            vertices[i * 7 + 6] = if (length > 1e-12)
                (-(normals[n] * m[3] + normals[n + 1] * m[7] + normals[n + 2] * m[11]) / (length * cameraLength)).toFloat()
                else 0f
        }
        if (vertices.any { !it.isFinite() } || positions.any { !it.isFinite() }) return null
        lastDiagnostics = "mp3d_${keep.count { it }}"
        return raw.withMeasuredSurface(vertices, positions, triangles)
    }

    private fun regression(x: DoubleArray, y: DoubleArray, keep: BooleanArray): DoubleArray? {
        var count = 0; var sx = 0.0; var sy = 0.0
        for (i in x.indices) if (keep[i]) { count++; sx += x[i]; sy += y[i] }
        if (count < 2) return null
        val mx = sx / count; val my = sy / count
        var xx = 0.0; var xy = 0.0
        for (i in x.indices) if (keep[i]) { xx += (x[i] - mx) * (x[i] - mx); xy += (x[i] - mx) * (y[i] - my) }
        if (xx < 1e-12) return null
        val slope = xy / xx
        return doubleArrayOf(slope, my - slope * mx)
    }

    private fun inverse3(a: DoubleArray): DoubleArray? {
        val cof = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        val det = a[0] * cof[0] + a[1] * cof[3] + a[2] * cof[6]
        if (abs(det) < 1e-12) return null
        return DoubleArray(9) { cof[it] / det }
    }
}
