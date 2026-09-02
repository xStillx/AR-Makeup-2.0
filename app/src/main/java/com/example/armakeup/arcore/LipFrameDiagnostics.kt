package com.example.armakeup.arcore

import com.example.armakeup.makeup.LipMeshTessellator
import java.util.Locale
import kotlin.math.hypot

/**
 * Frame trace that keeps MediaPipe screen mapping, ARCore correction, local refinement and
 * tessellation separate. All coordinates are emitted in absolute viewport pixels.
 */
internal class LipFrameDiagnostics {
    private var frameNumber = 0L
    private var previousSensorTimestampNs = 0L

    fun reset() {
        frameNumber = 0L
        previousSensorTimestampNs = 0L
    }

    fun capture(
        sensorTimestampNs: Long,
        renderTimestampNs: Long,
        viewportWidth: Int,
        viewportHeight: Int,
        mouthOpenness: Float,
        anchorCorrection: TimestampedLipAnchorTransport.Correction?,
        mediaPipeOuter: FloatArray,
        mediaPipeInner: FloatArray,
        correctedOuter: FloatArray,
        correctedInner: FloatArray,
        refinedOuter: FloatArray,
        refinedInner: FloatArray,
        tessellated: FloatArray,
        tessellator: LipMeshTessellator,
    ): Snapshot {
        require(viewportWidth > 0 && viewportHeight > 0)
        frameNumber++
        val newMeasurement = sensorTimestampNs != previousSensorTimestampNs
        previousSensorTimestampNs = sensorTimestampNs
        val mediaPipe = contourStage(mediaPipeOuter, mediaPipeInner, viewportWidth, viewportHeight)
        val corrected = contourStage(correctedOuter, correctedInner, viewportWidth, viewportHeight)
        val refined = contourStage(refinedOuter, refinedInner, viewportWidth, viewportHeight)
        val tessellation = tessellationStage(tessellated, tessellator, viewportWidth, viewportHeight)
        val translationX = (anchorCorrection?.translationX ?: 0f) * viewportWidth
        val translationY = (anchorCorrection?.translationY ?: 0f) * viewportHeight
        return Snapshot(
            frameNumber, newMeasurement, sensorTimestampNs, renderTimestampNs, mouthOpenness,
            anchorCorrection != null, Point(translationX, translationY),
            mediaPipe, corrected, refined, tessellation,
            maxAnchorResidual(mediaPipe, corrected, translationX, translationY),
            maxOf(
                distance(refined.lowerOuter, tessellation.lowerOuter),
                distance(refined.lowerInner, tessellation.lowerInner),
            ),
        )
    }

    private fun contourStage(
        outer: FloatArray,
        inner: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
    ): ContourStage {
        require(outer.size == inner.size)
        require(outer.size >= CONTOUR_POINT_COUNT * POINT_COMPONENT_COUNT)
        val left = point(outer, LEFT_CORNER_INDEX, viewportWidth, viewportHeight)
        val right = point(outer, RIGHT_CORNER_INDEX, viewportWidth, viewportHeight)
        val lowerOuter = point(outer, LOWER_CENTER_INDEX, viewportWidth, viewportHeight)
        val lowerInner = point(inner, LOWER_CENTER_INDEX, viewportWidth, viewportHeight)
        val center = Point((left.x + right.x) * 0.5f, (left.y + right.y) * 0.5f)
        val width = distance(left, right)
        val leftOffset = LEFT_CORNER_INDEX * POINT_COMPONENT_COUNT
        val rightOffset = RIGHT_CORNER_INDEX * POINT_COMPONENT_COUNT
        val lowerOffset = LOWER_CENTER_INDEX * POINT_COMPONENT_COUNT
        val normalizedCenterY = (outer[leftOffset + 1] + outer[rightOffset + 1]) * 0.5f
        val normalizedWidth = hypot(
            outer[rightOffset] - outer[leftOffset],
            outer[rightOffset + 1] - outer[leftOffset + 1],
        )
        return ContourStage(
            left, right, lowerOuter, lowerInner, center, width,
            (outer[lowerOffset + 1] - normalizedCenterY) / normalizedWidth,
            (inner[lowerOffset + 1] - normalizedCenterY) / normalizedWidth,
        )
    }

    private fun tessellationStage(
        vertices: FloatArray,
        tessellator: LipMeshTessellator,
        viewportWidth: Int,
        viewportHeight: Int,
    ): TessellationStage {
        require(vertices.size == tessellator.vertexCount * LipMeshTessellator.VERTEX_COMPONENT_COUNT)
        val ringsPerSample = tessellator.verticesPerLip / tessellator.samplesPerLip
        val lowerCenterSample = tessellator.samplesPerLip / 2
        val centerFirstRing = tessellator.verticesPerLip + lowerCenterSample * ringsPerSample
        return TessellationStage(
            lowerOuter = tessellatedPoint(vertices, centerFirstRing, viewportWidth, viewportHeight),
            lowerInner = tessellatedPoint(
                vertices,
                centerFirstRing + ringsPerSample - 1,
                viewportWidth,
                viewportHeight,
            ),
            previousOuter = tessellatedPoint(
                vertices,
                centerFirstRing - ringsPerSample,
                viewportWidth,
                viewportHeight,
            ),
            nextOuter = tessellatedPoint(
                vertices,
                centerFirstRing + ringsPerSample,
                viewportWidth,
                viewportHeight,
            ),
        )
    }

    private fun point(
        points: FloatArray,
        pointIndex: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Point {
        val offset = pointIndex * POINT_COMPONENT_COUNT
        return Point(points[offset] * viewportWidth, points[offset + 1] * viewportHeight)
    }

    private fun tessellatedPoint(
        vertices: FloatArray,
        vertexIndex: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Point {
        val offset = vertexIndex * LipMeshTessellator.VERTEX_COMPONENT_COUNT
        return Point(
            vertices[offset + LipMeshTessellator.X_COMPONENT_OFFSET] * viewportWidth,
            vertices[offset + LipMeshTessellator.Y_COMPONENT_OFFSET] * viewportHeight,
        )
    }

    private fun maxAnchorResidual(
        mediaPipe: ContourStage,
        corrected: ContourStage,
        translationX: Float,
        translationY: Float,
    ): Float = maxOf(
        residual(mediaPipe.left, corrected.left, translationX, translationY),
        residual(mediaPipe.right, corrected.right, translationX, translationY),
        residual(mediaPipe.lowerOuter, corrected.lowerOuter, translationX, translationY),
        residual(mediaPipe.lowerInner, corrected.lowerInner, translationX, translationY),
    )

    private fun residual(
        source: Point,
        corrected: Point,
        translationX: Float,
        translationY: Float,
    ): Float = hypot(
        corrected.x - source.x - translationX,
        corrected.y - source.y - translationY,
    )

    private fun distance(first: Point, second: Point): Float =
        hypot(second.x - first.x, second.y - first.y)

    fun toLogLine(snapshot: Snapshot): String = buildString(1024) {
        append("v=1,frame=").append(snapshot.frameNumber)
        append(",newMp=").append(if (snapshot.newMeasurement) 1 else 0)
        append(",sensorNs=").append(snapshot.sensorTimestampNs)
        append(",renderNs=").append(snapshot.renderTimestampNs)
        append(",ageMs=").append(decimal(
            (snapshot.renderTimestampNs - snapshot.sensorTimestampNs).coerceAtLeast(0L) /
                1_000_000f,
            3,
        ))
        append(",mouth=").append(decimal(snapshot.mouthOpenness, 4))
        append(",anchorOk=").append(if (snapshot.anchorAvailable) 1 else 0)
        append(",anchorDxPx=").append(decimal(snapshot.anchorTranslation.x, 3))
        append(",anchorDyPx=").append(decimal(snapshot.anchorTranslation.y, 3))
        appendStage("mp", snapshot.mediaPipe)
        appendStage("corr", snapshot.corrected)
        appendStage("ref", snapshot.refined)
        append(",tessLO=").appendPoint(snapshot.tessellation.lowerOuter)
        append(",tessLI=").appendPoint(snapshot.tessellation.lowerInner)
        append(",tessPrevO=").appendPoint(snapshot.tessellation.previousOuter)
        append(",tessNextO=").appendPoint(snapshot.tessellation.nextOuter)
        append(",anchorResidualPx=").append(decimal(snapshot.anchorResidualPx, 5))
        append(",tessResidualPx=").append(decimal(snapshot.tessellationResidualPx, 5))
    }

    private fun StringBuilder.appendStage(prefix: String, stage: ContourStage) {
        append(",").append(prefix).append("L=").appendPoint(stage.left)
        append(",").append(prefix).append("R=").appendPoint(stage.right)
        append(",").append(prefix).append("LO=").appendPoint(stage.lowerOuter)
        append(",").append(prefix).append("LI=").appendPoint(stage.lowerInner)
        append(",").append(prefix).append("C=").appendPoint(stage.center)
        append(",").append(prefix).append("W=").append(decimal(stage.widthPx, 2))
        append(",").append(prefix).append("LocalY=")
            .append(decimal(stage.lowerOuterLocalY, 5))
            .append(":")
            .append(decimal(stage.lowerInnerLocalY, 5))
    }

    private fun StringBuilder.appendPoint(point: Point): StringBuilder =
        append(decimal(point.x, 2)).append(":").append(decimal(point.y, 2))

    private fun decimal(value: Float, digits: Int): String =
        String.format(Locale.US, "%.$digits" + "f", value)

    data class Snapshot(
        val frameNumber: Long,
        val newMeasurement: Boolean,
        val sensorTimestampNs: Long,
        val renderTimestampNs: Long,
        val mouthOpenness: Float,
        val anchorAvailable: Boolean,
        val anchorTranslation: Point,
        val mediaPipe: ContourStage,
        val corrected: ContourStage,
        val refined: ContourStage,
        val tessellation: TessellationStage,
        val anchorResidualPx: Float,
        val tessellationResidualPx: Float,
    )

    data class ContourStage(
        val left: Point,
        val right: Point,
        val lowerOuter: Point,
        val lowerInner: Point,
        val center: Point,
        val widthPx: Float,
        val lowerOuterLocalY: Float,
        val lowerInnerLocalY: Float,
    )

    data class TessellationStage(
        val lowerOuter: Point,
        val lowerInner: Point,
        val previousOuter: Point,
        val nextOuter: Point,
    )

    data class Point(val x: Float, val y: Float)

    private companion object {
        const val POINT_COMPONENT_COUNT = 2
        const val CONTOUR_POINT_COUNT = 20
        const val LEFT_CORNER_INDEX = 0
        const val LOWER_CENTER_INDEX = 5
        const val RIGHT_CORNER_INDEX = 10
    }
}
