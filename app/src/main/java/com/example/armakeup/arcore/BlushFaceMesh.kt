package com.example.armakeup.arcore

import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.sqrt

/** Immutable ARCore geometry snapshot belonging to the captured camera timestamp. */
internal class BlushFaceMesh private constructor(
    val vertices: FloatArray,
    val indices: ShortArray,
    // Original face-local metres; material placement must not flatten the temple onto XY.
    val surfacePositions: FloatArray,
    val contour: FloatArray? = null,
    val contourParams: FloatArray? = null,
    val projectionMatrix: FloatArray? = null,
) {
    /** Keeps captured geometry immutable while attaching the measured cheek material coordinates. */
    fun withCheekCoordinates(
        coordinates: FloatArray,
        contour: FloatArray? = null,
        contourParams: FloatArray? = null,
    ): BlushFaceMesh {
        require(coordinates.size == vertices.size / COMPONENTS * 2)
        require(coordinates.all { it.isFinite() })
        require(contour == null || (contour.size == CONTOUR_POINTS * 4 && contour.all { it.isFinite() }))
        require(contourParams == null || (contourParams.size == 12 && contourParams.all { it.isFinite() }))
        val output = vertices.copyOf()
        for (point in coordinates.indices step 2) {
            val vertex = point / 2 * COMPONENTS
            output[vertex + 4] = coordinates[point]
            output[vertex + 5] = coordinates[point + 1]
        }
        return BlushFaceMesh(output, indices, surfacePositions, contour, contourParams, projectionMatrix)
    }

    /** Replaces only the blush surface; the captured ARCore snapshot stays immutable. */
    fun withMeasuredSurface(output: FloatArray, positions: FloatArray, triangles: ShortArray): BlushFaceMesh {
        require(output.size % COMPONENTS == 0 && positions.size == output.size / COMPONENTS * 3)
        require(output.all { it.isFinite() } && positions.all { it.isFinite() })
        require(triangles.size % 3 == 0 && triangles.all { (it.toInt() and 0xffff) < positions.size / 3 })
        return BlushFaceMesh(output, triangles, positions, projectionMatrix = projectionMatrix)
    }

    companion object {
        // clip xyzw, face-local normalized xy (material chart UV after placement), facing cosine.
        const val COMPONENTS = 7
        const val CONTOUR_POINTS = 24

        fun capture(
            positions: FloatBuffer,
            normals: FloatBuffer,
            triangles: ShortBuffer,
            mvp: FloatArray,
            viewModel: FloatArray,
        ): BlushFaceMesh? {
            val count = positions.limit() / 3
            if (count < 3 || normals.limit() < count * 3 || triangles.limit() < 3 ||
                mvp.size != 16 || viewModel.size != 16 ||
                mvp.any { !it.isFinite() } || viewModel.any { !it.isFinite() }) return null
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (i in 0 until count) {
                val x = positions[i * 3]
                val y = positions[i * 3 + 1]
                val z = positions[i * 3 + 2]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
            val width = maxX - minX
            val height = maxY - minY
            if (!width.isFinite() || !height.isFinite() || width < 0.02f || height < 0.02f) return null
            val output = FloatArray(count * COMPONENTS)
            val surface = FloatArray(count * 3)
            for (i in 0 until count) {
                val p = i * 3
                val target = i * COMPONENTS
                val x = positions[p]
                val y = positions[p + 1]
                val z = positions[p + 2]
                surface[p] = x
                surface[p + 1] = y
                surface[p + 2] = z
                for (axis in 0..3) {
                    output[target + axis] = mvp[axis] * x + mvp[4 + axis] * y +
                        mvp[8 + axis] * z + mvp[12 + axis]
                    if (!output[target + axis].isFinite()) return null
                }
                if (output[target + 3] <= 0f) return null
                output[target + 4] = (x - (minX + maxX) * 0.5f) / (width * 0.5f)
                output[target + 5] = (y - minY) / height
                val nx = normals[p]
                val ny = normals[p + 1]
                val nz = normals[p + 2]
                val vx = viewModel[0] * nx + viewModel[4] * ny + viewModel[8] * nz
                val vy = viewModel[1] * nx + viewModel[5] * ny + viewModel[9] * nz
                val vz = viewModel[2] * nx + viewModel[6] * ny + viewModel[10] * nz
                val length = sqrt(vx * vx + vy * vy + vz * vz)
                if (!length.isFinite() || length < 1e-6f) return null
                output[target + 6] = vz / length
            }
            val indices = ShortArray(triangles.limit()) { triangles[it] }
            if (indices.size % 3 != 0 || indices.any { (it.toInt() and 0xffff) >= count }) return null
            return BlushFaceMesh(output, indices, surface, projectionMatrix = mvp.copyOf())
        }
    }
}
