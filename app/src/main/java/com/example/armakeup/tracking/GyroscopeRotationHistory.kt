package com.example.armakeup.tracking

import kotlin.math.abs

/**
 * Timestamped short-horizon integral of Android gyroscope samples.
 *
 * SensorEvent timestamps and Camera2 SENSOR_TIMESTAMP normally share CLOCK_BOOTTIME. Keeping the
 * integral in that clock lets the renderer ask only for device rotation between the effective
 * landmark time and the camera buffer that is actually being displayed.
 */
internal class GyroscopeRotationHistory(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maximumSampleGapNs: Long = DEFAULT_MAXIMUM_SAMPLE_GAP_NS,
    private val maximumQueryIntervalNs: Long = DEFAULT_MAXIMUM_QUERY_INTERVAL_NS,
) {
    data class Rotation(
        val xRadians: Float,
        val yRadians: Float,
        val zRadians: Float,
    ) {
        companion object {
            val ZERO = Rotation(0f, 0f, 0f)
        }
    }

    private data class Sample(
        val timestampNs: Long,
        val cumulativeX: Double,
        val cumulativeY: Double,
        val cumulativeZ: Double,
        val velocityX: Float,
        val velocityY: Float,
        val velocityZ: Float,
    )

    private data class Integral(
        val x: Double,
        val y: Double,
        val z: Double,
    )

    private val samples = ArrayDeque<Sample>(capacity)

    init {
        require(capacity >= 2)
        require(maximumSampleGapNs > 0L)
        require(maximumQueryIntervalNs > 0L)
    }

    @Synchronized
    fun addSample(
        timestampNs: Long,
        angularVelocityX: Float,
        angularVelocityY: Float,
        angularVelocityZ: Float,
    ) {
        if (
            timestampNs <= 0L ||
            !angularVelocityX.isFinite() ||
            !angularVelocityY.isFinite() ||
            !angularVelocityZ.isFinite()
        ) {
            return
        }
        val velocityX = angularVelocityX.coerceIn(-MAXIMUM_ANGULAR_SPEED, MAXIMUM_ANGULAR_SPEED)
        val velocityY = angularVelocityY.coerceIn(-MAXIMUM_ANGULAR_SPEED, MAXIMUM_ANGULAR_SPEED)
        val velocityZ = angularVelocityZ.coerceIn(-MAXIMUM_ANGULAR_SPEED, MAXIMUM_ANGULAR_SPEED)
        val previous = samples.lastOrNull()
        if (
            previous == null ||
            timestampNs <= previous.timestampNs ||
            timestampNs - previous.timestampNs > maximumSampleGapNs
        ) {
            samples.clear()
            samples.addLast(
                Sample(timestampNs, 0.0, 0.0, 0.0, velocityX, velocityY, velocityZ),
            )
            return
        }

        val deltaSeconds = (timestampNs - previous.timestampNs) / NANOS_PER_SECOND
        samples.addLast(
            Sample(
                timestampNs = timestampNs,
                cumulativeX = previous.cumulativeX +
                    (previous.velocityX + velocityX) * 0.5 * deltaSeconds,
                cumulativeY = previous.cumulativeY +
                    (previous.velocityY + velocityY) * 0.5 * deltaSeconds,
                cumulativeZ = previous.cumulativeZ +
                    (previous.velocityZ + velocityZ) * 0.5 * deltaSeconds,
                velocityX = velocityX,
                velocityY = velocityY,
                velocityZ = velocityZ,
            ),
        )
        while (samples.size > capacity) samples.removeFirst()
    }

    /** Returns device rotation from [fromTimestampNs] to [toTimestampNs], or null on any gap. */
    @Synchronized
    fun rotationBetween(fromTimestampNs: Long, toTimestampNs: Long): Rotation? {
        if (
            fromTimestampNs <= 0L ||
            toTimestampNs <= 0L ||
            abs(toTimestampNs - fromTimestampNs) > maximumQueryIntervalNs ||
            samples.size < 2
        ) {
            return null
        }
        val from = integralAt(fromTimestampNs) ?: return null
        val to = integralAt(toTimestampNs) ?: return null
        return Rotation(
            xRadians = (to.x - from.x).toFloat(),
            yRadians = (to.y - from.y).toFloat(),
            zRadians = (to.z - from.z).toFloat(),
        )
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    private fun integralAt(timestampNs: Long): Integral? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        if (timestampNs !in first.timestampNs..last.timestampNs) return null
        if (timestampNs == first.timestampNs) {
            return Integral(first.cumulativeX, first.cumulativeY, first.cumulativeZ)
        }

        var previous = first
        for (current in samples) {
            if (current.timestampNs <= previous.timestampNs) continue
            if (timestampNs <= current.timestampNs) {
                val fraction = (timestampNs - previous.timestampNs).toDouble() /
                    (current.timestampNs - previous.timestampNs).toDouble()
                return Integral(
                    x = lerp(previous.cumulativeX, current.cumulativeX, fraction),
                    y = lerp(previous.cumulativeY, current.cumulativeY, fraction),
                    z = lerp(previous.cumulativeZ, current.cumulativeZ, fraction),
                )
            }
            previous = current
        }
        return Integral(last.cumulativeX, last.cumulativeY, last.cumulativeZ)
    }

    private fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    companion object {
        private const val DEFAULT_CAPACITY = 320
        private const val DEFAULT_MAXIMUM_SAMPLE_GAP_NS = 50_000_000L
        private const val DEFAULT_MAXIMUM_QUERY_INTERVAL_NS = 160_000_000L
        private const val MAXIMUM_ANGULAR_SPEED = 20f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
