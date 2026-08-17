package com.example.armakeup.tracking

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Physical front-camera calibration expressed in normalized unrotated sensor coordinates. */
data class CameraProjectionCalibration(
    val rawFocalXNormalized: Float,
    val rawFocalYNormalized: Float,
) {
    init {
        require(rawFocalXNormalized.isFinite() && rawFocalXNormalized > 0f)
        require(rawFocalYNormalized.isFinite() && rawFocalYNormalized > 0f)
    }

    fun displayFocalX(rotationDegrees: Int): Float =
        if (normalizedRotation(rotationDegrees) % HALF_ROTATION == 0) {
            rawFocalXNormalized
        } else {
            rawFocalYNormalized
        }

    fun displayFocalY(rotationDegrees: Int): Float =
        if (normalizedRotation(rotationDegrees) % HALF_ROTATION == 0) {
            rawFocalYNormalized
        } else {
            rawFocalXNormalized
        }

    companion object {
        fun fromPhysicalSensor(
            focalLengthMillimeters: Float,
            sensorWidthMillimeters: Float,
            sensorHeightMillimeters: Float,
        ): CameraProjectionCalibration? {
            if (
                !focalLengthMillimeters.isFinite() || focalLengthMillimeters <= 0f ||
                !sensorWidthMillimeters.isFinite() || sensorWidthMillimeters <= 0f ||
                !sensorHeightMillimeters.isFinite() || sensorHeightMillimeters <= 0f
            ) {
                return null
            }
            return CameraProjectionCalibration(
                rawFocalXNormalized = focalLengthMillimeters / sensorWidthMillimeters,
                rawFocalYNormalized = focalLengthMillimeters / sensorHeightMillimeters,
            )
        }

        private fun normalizedRotation(rotationDegrees: Int): Int =
            ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION

        private const val HALF_ROTATION = 180
        private const val FULL_ROTATION = 360
    }
}

/**
 * Converts timestamped device rotation into one bounded global correction in display space.
 *
 * Pitch/yaw use the physical camera field of view, while roll rotates every point around the
 * optical centre. No landmark-local deformation is changed.
 */
internal class GyroscopeLipCompensator(
    private val rotationHistory: GyroscopeRotationHistory,
) {
    data class Correction(
        val applied: Boolean,
        val intervalMs: Float,
        val rotationX: Float,
        val rotationY: Float,
        val rotationZ: Float,
        val translationX: Float,
        val translationY: Float,
        val rollRadians: Float,
    ) {
        fun mapX(x: Float, y: Float): Float {
            if (!applied) return x
            val centeredX = x - OPTICAL_CENTER
            val centeredY = y - OPTICAL_CENTER
            return OPTICAL_CENTER +
                cos(rollRadians) * centeredX - sin(rollRadians) * centeredY + translationX
        }

        fun mapY(x: Float, y: Float): Float {
            if (!applied) return y
            val centeredX = x - OPTICAL_CENTER
            val centeredY = y - OPTICAL_CENTER
            return OPTICAL_CENTER +
                sin(rollRadians) * centeredX + cos(rollRadians) * centeredY + translationY
        }

        val translationMagnitude: Float
            get() = hypot(translationX, translationY)

        companion object {
            val NONE = Correction(
                applied = false,
                intervalMs = Float.NaN,
                rotationX = 0f,
                rotationY = 0f,
                rotationZ = 0f,
                translationX = 0f,
                translationY = 0f,
                rollRadians = 0f,
            )
        }
    }

    fun correctionFor(
        meshSensorTimestampNs: Long,
        cameraSensorTimestampNs: Long,
        cameraRotationDegrees: Int,
        mirrorHorizontal: Boolean,
        displayRotation: Int,
        calibration: CameraProjectionCalibration?,
    ): Correction {
        calibration ?: return Correction.NONE
        val deviceRotation = rotationHistory.rotationBetween(
            meshSensorTimestampNs,
            cameraSensorTimestampNs,
        ) ?: return Correction.NONE
        val displayAxes = remapToDisplayAxes(deviceRotation, displayRotation)
        val magnitude = hypot(
            hypot(displayAxes.xRadians, displayAxes.yRadians),
            displayAxes.zRadians,
        )
        if (!magnitude.isFinite() || magnitude < MINIMUM_ROTATION_RADIANS) {
            return Correction.NONE
        }

        val rotationX = displayAxes.xRadians.coerceIn(-MAXIMUM_ROTATION, MAXIMUM_ROTATION)
        val rotationY = displayAxes.yRadians.coerceIn(-MAXIMUM_ROTATION, MAXIMUM_ROTATION)
        val rotationZ = displayAxes.zRadians.coerceIn(-MAXIMUM_ROTATION, MAXIMUM_ROTATION)
        val mirrorSign = if (mirrorHorizontal) 1f else -1f
        val translationX = (
            mirrorSign * calibration.displayFocalX(cameraRotationDegrees) * tan(rotationY)
        ).coerceIn(-MAXIMUM_TRANSLATION, MAXIMUM_TRANSLATION)
        val translationY = (
            -calibration.displayFocalY(cameraRotationDegrees) * tan(rotationX)
        ).coerceIn(-MAXIMUM_TRANSLATION, MAXIMUM_TRANSLATION)
        val roll = (if (mirrorHorizontal) -rotationZ else rotationZ)
            .coerceIn(-MAXIMUM_ROLL, MAXIMUM_ROLL)
        return Correction(
            applied = true,
            intervalMs = abs(cameraSensorTimestampNs - meshSensorTimestampNs) /
                NANOS_PER_MILLISECOND,
            rotationX = rotationX,
            rotationY = rotationY,
            rotationZ = rotationZ,
            translationX = translationX,
            translationY = translationY,
            rollRadians = roll,
        )
    }

    internal fun remapToDisplayAxes(
        rotation: GyroscopeRotationHistory.Rotation,
        displayRotation: Int,
    ): GyroscopeRotationHistory.Rotation = when (displayRotation) {
        DISPLAY_ROTATION_0 -> rotation
        DISPLAY_ROTATION_90 -> GyroscopeRotationHistory.Rotation(
            xRadians = -rotation.yRadians,
            yRadians = rotation.xRadians,
            zRadians = rotation.zRadians,
        )
        DISPLAY_ROTATION_180 -> GyroscopeRotationHistory.Rotation(
            xRadians = -rotation.xRadians,
            yRadians = -rotation.yRadians,
            zRadians = rotation.zRadians,
        )
        DISPLAY_ROTATION_270 -> GyroscopeRotationHistory.Rotation(
            xRadians = rotation.yRadians,
            yRadians = -rotation.xRadians,
            zRadians = rotation.zRadians,
        )
        else -> rotation
    }

    companion object {
        private const val DISPLAY_ROTATION_0 = 0
        private const val DISPLAY_ROTATION_90 = 1
        private const val DISPLAY_ROTATION_180 = 2
        private const val DISPLAY_ROTATION_270 = 3
        private const val OPTICAL_CENTER = 0.5f
        private const val MINIMUM_ROTATION_RADIANS = 0.00035f
        private const val MAXIMUM_ROTATION = 0.14f
        private const val MAXIMUM_TRANSLATION = 0.12f
        private const val MAXIMUM_ROLL = 0.14f
        private const val NANOS_PER_MILLISECOND = 1_000_000f
    }
}
