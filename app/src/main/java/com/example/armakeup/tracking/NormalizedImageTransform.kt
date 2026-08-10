package com.example.armakeup.tracking

/**
 * Maps normalized landmarks from the raw analysis-buffer orientation into the orientation
 * displayed by PreviewView. Camera rotation is applied first, then front-camera mirroring.
 */
class NormalizedImageTransform(
    rotationDegrees: Int,
    private val mirrorHorizontal: Boolean,
) {
    private val rotationDegrees = ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION

    init {
        require(this.rotationDegrees % QUARTER_ROTATION == 0) {
            "Rotation must be a multiple of 90 degrees"
        }
    }

    fun mapX(normalizedX: Float, normalizedY: Float): Float {
        val rotatedX = when (rotationDegrees) {
            0 -> normalizedX
            90 -> 1f - normalizedY
            180 -> 1f - normalizedX
            270 -> normalizedY
            else -> error("Unsupported normalized rotation: $rotationDegrees")
        }
        return if (mirrorHorizontal) 1f - rotatedX else rotatedX
    }

    fun mapY(normalizedX: Float, normalizedY: Float): Float =
        when (rotationDegrees) {
            0 -> normalizedY
            90 -> normalizedX
            180 -> 1f - normalizedY
            270 -> 1f - normalizedX
            else -> error("Unsupported normalized rotation: $rotationDegrees")
        }

    companion object {
        private const val QUARTER_ROTATION = 90
        private const val FULL_ROTATION = 360
    }
}
