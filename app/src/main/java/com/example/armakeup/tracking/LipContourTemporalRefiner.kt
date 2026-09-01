package com.example.armakeup.tracking

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Stabilizes only lip-local deformation.
 *
 * Global motion is intentionally excluded: the caller reconstructs the filtered local shape around
 * the current measured center and the timestamped ARCore anchor remains responsible for transport.
 */
internal class LipContourTemporalRefiner(
    private val staticResponse: Float = DEFAULT_STATIC_RESPONSE,
    private val dynamicResponse: Float = DEFAULT_DYNAMIC_RESPONSE,
) {
    private var previousTimestampNs = 0L
    private var previousOuterLocal: FloatArray? = null
    private var previousInnerLocal: FloatArray? = null
    private var targetOuterLocal: FloatArray? = null
    private var targetInnerLocal: FloatArray? = null
    private var previousMouthOpenness: Float? = null
    private var previousRenderTimestampNs = 0L

    init {
        require(staticResponse in 0f..1f)
        require(dynamicResponse in staticResponse..1f)
    }

    fun refine(
        measuredOuter: FloatArray,
        measuredInner: FloatArray,
        sensorTimestampNs: Long,
        renderTimestampNs: Long,
        geometryConfidence: Float,
        mouthOpenness: Float,
        outputOuter: FloatArray,
        outputInner: FloatArray,
    ): Result {
        require(measuredOuter.size == measuredInner.size)
        require(measuredOuter.size >= MINIMUM_COMPONENT_COUNT)
        require(measuredOuter.size % POINT_COMPONENT_COUNT == 0)
        require(outputOuter.size == measuredOuter.size)
        require(outputInner.size == measuredInner.size)
        require(geometryConfidence in 0f..1f)
        require(mouthOpenness in 0f..1f)

        val centerX = (x(measuredOuter, LEFT_CORNER_INDEX) + x(measuredOuter, RIGHT_CORNER_INDEX)) * 0.5f
        val centerY = (y(measuredOuter, LEFT_CORNER_INDEX) + y(measuredOuter, RIGHT_CORNER_INDEX)) * 0.5f
        val width = hypot(
            x(measuredOuter, RIGHT_CORNER_INDEX) - x(measuredOuter, LEFT_CORNER_INDEX),
            y(measuredOuter, RIGHT_CORNER_INDEX) - y(measuredOuter, LEFT_CORNER_INDEX),
        )
        if (!width.isFinite() || width <= MINIMUM_WIDTH) {
            measuredOuter.copyInto(outputOuter)
            measuredInner.copyInto(outputInner)
            reset()
            return Result(response = 1f, localMotion = 0f, accepted = false)
        }

        val previousOuter = previousOuterLocal
        val previousInner = previousInnerLocal
        if (previousOuter != null && previousInner != null && sensorTimestampNs <= previousTimestampNs) {
            if (
                sensorTimestampNs == previousTimestampNs &&
                renderTimestampNs > previousRenderTimestampNs
            ) {
                val targetOuter = targetOuterLocal
                val targetInner = targetInnerLocal
                if (targetOuter != null && targetInner != null) {
                    continueLowerRenderTransition(
                        previousOuter,
                        previousInner,
                        targetOuter,
                        targetInner,
                    )
                }
                previousRenderTimestampNs = renderTimestampNs
            }
            fromLocal(previousOuter, centerX, centerY, width, outputOuter)
            fromLocal(previousInner, centerX, centerY, width, outputInner)
            return Result(response = 0f, localMotion = 0f, accepted = true)
        }
        val currentOuterLocal = toLocal(measuredOuter, centerX, centerY, width)
        val currentInnerLocal = toLocal(measuredInner, centerX, centerY, width)

        val response: Float
        val localMotion: Float
        var diagnostics = TransitionDiagnostics()
        if (previousOuter == null || previousInner == null) {
            previousOuterLocal = currentOuterLocal
            previousInnerLocal = currentInnerLocal
            targetOuterLocal = currentOuterLocal.copyOf()
            targetInnerLocal = currentInnerLocal.copyOf()
            previousTimestampNs = sensorTimestampNs
            previousRenderTimestampNs = renderTimestampNs
            previousMouthOpenness = mouthOpenness
            response = 1f
            localMotion = 0f
        } else {
            val intervalSeconds = (
                (sensorTimestampNs - previousTimestampNs).coerceAtLeast(1L) /
                    NANOS_PER_SECOND.toFloat()
            )
            val normalizedDelta = maxPointDelta(
                currentOuterLocal,
                currentInnerLocal,
                previousOuter,
                previousInner,
            )
            localMotion = normalizedDelta / intervalSeconds
            val motionResponse = smoothUnit(
                (localMotion - STATIC_LOCAL_SPEED) /
                    (DYNAMIC_LOCAL_SPEED - STATIC_LOCAL_SPEED),
            )
            response = (
                staticResponse +
                    (dynamicResponse - staticResponse) * motionResponse
            ) * (MINIMUM_CONFIDENCE_RESPONSE +
                (1f - MINIMUM_CONFIDENCE_RESPONSE) * geometryConfidence)

            val priorMouthOpenness = previousMouthOpenness ?: mouthOpenness
            val opennessSpeed = abs(mouthOpenness - priorMouthOpenness) / intervalSeconds
            val opennessTransition = smoothUnit(
                (opennessSpeed - MOUTH_TRANSITION_START_SPEED) /
                    (MOUTH_TRANSITION_FULL_SPEED - MOUTH_TRANSITION_START_SPEED),
            )
            val maximumVerticalResponse = minOf(response, MOUTH_TRANSITION_MAX_RESPONSE)
            val upperVerticalResponse = response +
                (maximumVerticalResponse - response) * opennessTransition
            val lowerCenterResponse = upperVerticalResponse
            val lowerThicknessResponse = upperVerticalResponse
            targetOuterLocal = currentOuterLocal.copyOf()
            targetInnerLocal = currentInnerLocal.copyOf()

            val previousUpperOuterY = y(previousOuter, UPPER_CENTER_INDEX)
            val previousUpperInnerY = y(previousInner, UPPER_CENTER_INDEX)
            val previousLowerOuterY = y(previousOuter, LOWER_CENTER_INDEX)
            val previousLowerInnerY = y(previousInner, LOWER_CENTER_INDEX)
            val rawUpperOuterDelta =
                y(currentOuterLocal, UPPER_CENTER_INDEX) - previousUpperOuterY
            val rawUpperInnerDelta =
                y(currentInnerLocal, UPPER_CENTER_INDEX) - previousUpperInnerY
            val rawLowerOuterDelta =
                y(currentOuterLocal, LOWER_CENTER_INDEX) - previousLowerOuterY
            val rawLowerInnerDelta =
                y(currentInnerLocal, LOWER_CENTER_INDEX) - previousLowerInnerY

            blendContoursInto(
                previousOuter = previousOuter,
                previousInner = previousInner,
                currentOuter = currentOuterLocal,
                currentInner = currentInnerLocal,
                response = response,
                upperVerticalResponse = upperVerticalResponse,
                lowerCenterResponse = lowerCenterResponse,
                lowerThicknessResponse = lowerThicknessResponse,
            )
            val filteredUpperOuterDelta =
                y(previousOuter, UPPER_CENTER_INDEX) - previousUpperOuterY
            val filteredUpperInnerDelta =
                y(previousInner, UPPER_CENTER_INDEX) - previousUpperInnerY
            val filteredLowerOuterDelta =
                y(previousOuter, LOWER_CENTER_INDEX) - previousLowerOuterY
            val filteredLowerInnerDelta =
                y(previousInner, LOWER_CENTER_INDEX) - previousLowerInnerY
            diagnostics = TransitionDiagnostics(
                mouthOpennessSpeed = opennessSpeed,
                upperVerticalResponse = upperVerticalResponse,
                lowerVerticalResponse = lowerCenterResponse,
                lowerThicknessResponse = lowerThicknessResponse,
                rawUpperCenterDelta = (rawUpperOuterDelta + rawUpperInnerDelta) * 0.5f,
                filteredUpperCenterDelta =
                    (filteredUpperOuterDelta + filteredUpperInnerDelta) * 0.5f,
                rawUpperThicknessDelta = rawUpperInnerDelta - rawUpperOuterDelta,
                filteredUpperThicknessDelta =
                    filteredUpperInnerDelta - filteredUpperOuterDelta,
                rawLowerCenterDelta = (rawLowerOuterDelta + rawLowerInnerDelta) * 0.5f,
                filteredLowerCenterDelta =
                    (filteredLowerOuterDelta + filteredLowerInnerDelta) * 0.5f,
                rawLowerThicknessDelta = rawLowerOuterDelta - rawLowerInnerDelta,
                filteredLowerThicknessDelta =
                    filteredLowerOuterDelta - filteredLowerInnerDelta,
            )
            previousTimestampNs = sensorTimestampNs
            previousRenderTimestampNs = renderTimestampNs
            previousMouthOpenness = mouthOpenness
        }

        val filteredOuter = previousOuterLocal ?: currentOuterLocal
        val filteredInner = previousInnerLocal ?: currentInnerLocal
        fromLocal(filteredOuter, centerX, centerY, width, outputOuter)
        fromLocal(filteredInner, centerX, centerY, width, outputInner)
        return Result(
            response = response,
            localMotion = localMotion,
            accepted = true,
            diagnostics = diagnostics,
        )
    }

    fun reset() {
        previousTimestampNs = 0L
        previousOuterLocal = null
        previousInnerLocal = null
        targetOuterLocal = null
        targetInnerLocal = null
        previousMouthOpenness = null
        previousRenderTimestampNs = 0L
    }

    private fun toLocal(
        points: FloatArray,
        centerX: Float,
        centerY: Float,
        width: Float,
    ): FloatArray {
        val output = FloatArray(points.size)
        var index = 0
        while (index < points.size) {
            output[index] = (points[index] - centerX) / width
            output[index + 1] = (points[index + 1] - centerY) / width
            index += POINT_COMPONENT_COUNT
        }
        return output
    }

    private fun fromLocal(
        local: FloatArray,
        centerX: Float,
        centerY: Float,
        width: Float,
        output: FloatArray,
    ) {
        var index = 0
        while (index < local.size) {
            output[index] = centerX + local[index] * width
            output[index + 1] = centerY + local[index + 1] * width
            index += POINT_COMPONENT_COUNT
        }
    }

    private fun maxPointDelta(
        currentOuter: FloatArray,
        currentInner: FloatArray,
        previousOuter: FloatArray,
        previousInner: FloatArray,
    ): Float = maxOf(
        maxPointDelta(currentOuter, previousOuter),
        maxPointDelta(currentInner, previousInner),
    )

    private fun maxPointDelta(current: FloatArray, previous: FloatArray): Float {
        var maximum = 0f
        var index = 0
        while (index < current.size) {
            maximum = maxOf(
                maximum,
                hypot(
                    current[index] - previous[index],
                    current[index + 1] - previous[index + 1],
                ),
            )
            index += POINT_COMPONENT_COUNT
        }
        return maximum
    }

    private fun blendContoursInto(
        previousOuter: FloatArray,
        previousInner: FloatArray,
        currentOuter: FloatArray,
        currentInner: FloatArray,
        response: Float,
        upperVerticalResponse: Float,
        lowerCenterResponse: Float,
        lowerThicknessResponse: Float,
    ) {
        repeat(previousOuter.size / POINT_COMPONENT_COUNT) { pointIndex ->
            val offset = pointIndex * POINT_COMPONENT_COUNT
            previousOuter[offset] +=
                (currentOuter[offset] - previousOuter[offset]) * response
            previousInner[offset] +=
                (currentInner[offset] - previousInner[offset]) * response
            when (pointIndex) {
                in UPPER_INTERIOR_POINT_RANGE -> {
                    previousOuter[offset + 1] +=
                        (currentOuter[offset + 1] - previousOuter[offset + 1]) *
                            upperVerticalResponse
                    previousInner[offset + 1] +=
                        (currentInner[offset + 1] - previousInner[offset + 1]) *
                            upperVerticalResponse
                }

                in LOWER_INTERIOR_POINT_RANGE -> {
                    val previousCenter =
                        (previousOuter[offset + 1] + previousInner[offset + 1]) * 0.5f
                    val currentCenter =
                        (currentOuter[offset + 1] + currentInner[offset + 1]) * 0.5f
                    val previousHalfThickness =
                        (previousOuter[offset + 1] - previousInner[offset + 1]) * 0.5f
                    val currentHalfThickness =
                        (currentOuter[offset + 1] - currentInner[offset + 1]) * 0.5f
                    val filteredCenter = previousCenter +
                        (currentCenter - previousCenter) * lowerCenterResponse
                    val filteredHalfThickness = previousHalfThickness +
                        (currentHalfThickness - previousHalfThickness) *
                            lowerThicknessResponse
                    previousOuter[offset + 1] = filteredCenter + filteredHalfThickness
                    previousInner[offset + 1] = filteredCenter - filteredHalfThickness
                }

                else -> {
                    previousOuter[offset + 1] +=
                        (currentOuter[offset + 1] - previousOuter[offset + 1]) * response
                    previousInner[offset + 1] +=
                        (currentInner[offset + 1] - previousInner[offset + 1]) * response
                }
            }
        }
    }

    private fun continueLowerRenderTransition(
        previousOuter: FloatArray,
        previousInner: FloatArray,
        targetOuter: FloatArray,
        targetInner: FloatArray,
    ) {
        for (pointIndex in LOWER_INTERIOR_POINT_RANGE) {
            val offset = pointIndex * POINT_COMPONENT_COUNT + 1
            val previousCenter = (previousOuter[offset] + previousInner[offset]) * 0.5f
            val targetCenter = (targetOuter[offset] + targetInner[offset]) * 0.5f
            val previousHalfThickness =
                (previousOuter[offset] - previousInner[offset]) * 0.5f
            val targetHalfThickness = (targetOuter[offset] - targetInner[offset]) * 0.5f
            val center = previousCenter +
                (targetCenter - previousCenter) * LOWER_RENDER_CONTINUATION_RESPONSE
            val halfThickness = previousHalfThickness +
                (targetHalfThickness - previousHalfThickness) *
                    LOWER_RENDER_CONTINUATION_RESPONSE
            previousOuter[offset] = center + halfThickness
            previousInner[offset] = center - halfThickness
        }
    }

    private fun x(points: FloatArray, point: Int): Float = points[point * POINT_COMPONENT_COUNT]

    private fun y(points: FloatArray, point: Int): Float =
        points[point * POINT_COMPONENT_COUNT + 1]

    private fun smoothUnit(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    data class Result(
        val response: Float,
        val localMotion: Float,
        val accepted: Boolean,
        val diagnostics: TransitionDiagnostics = TransitionDiagnostics(),
    )

    data class TransitionDiagnostics(
        val mouthOpennessSpeed: Float = 0f,
        val upperVerticalResponse: Float = 0f,
        val lowerVerticalResponse: Float = 0f,
        val lowerThicknessResponse: Float = 0f,
        val rawUpperCenterDelta: Float = 0f,
        val filteredUpperCenterDelta: Float = 0f,
        val rawUpperThicknessDelta: Float = 0f,
        val filteredUpperThicknessDelta: Float = 0f,
        val rawLowerCenterDelta: Float = 0f,
        val filteredLowerCenterDelta: Float = 0f,
        val rawLowerThicknessDelta: Float = 0f,
        val filteredLowerThicknessDelta: Float = 0f,
    )

    private companion object {
        const val POINT_COMPONENT_COUNT = 2
        const val MINIMUM_COMPONENT_COUNT = 6
        const val LEFT_CORNER_INDEX = 0
        const val LOWER_CENTER_INDEX = 5
        const val RIGHT_CORNER_INDEX = 10
        const val UPPER_CENTER_INDEX = 15
        const val MINIMUM_WIDTH = 1e-5f
        const val DEFAULT_STATIC_RESPONSE = 0.32f
        const val DEFAULT_DYNAMIC_RESPONSE = 0.94f
        const val MINIMUM_CONFIDENCE_RESPONSE = 0.58f
        const val STATIC_LOCAL_SPEED = 0.08f
        const val DYNAMIC_LOCAL_SPEED = 1.40f
        const val MOUTH_TRANSITION_START_SPEED = 0.55f
        const val MOUTH_TRANSITION_FULL_SPEED = 2.40f
        const val MOUTH_TRANSITION_MAX_RESPONSE = 0.56f
        const val LOWER_RENDER_CONTINUATION_RESPONSE = 0.50f
        const val NANOS_PER_SECOND = 1_000_000_000L
        val LOWER_INTERIOR_POINT_RANGE = 1..9
        val UPPER_INTERIOR_POINT_RANGE = 11..19
    }
}
