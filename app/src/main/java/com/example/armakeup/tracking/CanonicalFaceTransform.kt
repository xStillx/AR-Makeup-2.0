package com.example.armakeup.tracking

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/** A small immutable vector used by the shadow canonical-face transform contract. */
data class FaceTransformVector3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    val length: Float
        get() = sqrt(x * x + y * y + z * z)

    infix fun dot(other: FaceTransformVector3): Float =
        x * other.x + y * other.y + z * other.z

    infix fun cross(other: FaceTransformVector3): FaceTransformVector3 =
        FaceTransformVector3(
            x = y * other.z - z * other.y,
            y = z * other.x - x * other.z,
            z = x * other.y - y * other.x,
        )

    operator fun times(scale: Float): FaceTransformVector3 =
        FaceTransformVector3(x * scale, y * scale, z * scale)

    operator fun plus(other: FaceTransformVector3): FaceTransformVector3 =
        FaceTransformVector3(x + other.x, y + other.y, z + other.z)

    fun normalizedOrNull(): FaceTransformVector3? {
        val magnitude = length
        if (!magnitude.isFinite() || magnitude <= MINIMUM_AXIS_LENGTH) return null
        return this * (1f / magnitude)
    }

    private companion object {
        const val MINIMUM_AXIS_LENGTH = 1e-6f
    }
}

data class ImagePlaneDirection(
    val x: Float,
    val y: Float,
) {
    val angleRadians: Float
        get() = atan2(y, x)
}

/**
 * Parsed MediaPipe canonical-face transform.
 *
 * MediaPipe's Java API exposes a flat **column-major** 4x4 matrix that maps the canonical face
 * model (centimetres) into its right-handed metric camera space. The virtual camera is at the
 * origin and looks along -Z. This class deliberately keeps that raw metric-space contract separate
 * from PreviewView rotation, front-camera mirroring and fill-centre crop.
 */
class CanonicalFaceTransform private constructor(
    val axisX: FaceTransformVector3,
    val axisY: FaceTransformVector3,
    val axisZ: FaceTransformVector3,
    val translation: FaceTransformVector3,
    val affineBottomRowError: Float,
) {
    val scaleX: Float
        get() = axisX.length
    val scaleY: Float
        get() = axisY.length
    val scaleZ: Float
        get() = axisZ.length
    val uniformScale: Float
        get() = (scaleX + scaleY + scaleZ) / AXIS_COUNT

    /** Positive for a right-handed basis, negative for a reflection. */
    val normalizedDeterminant: Float
        get() {
            val x = axisX.normalizedOrNull() ?: return Float.NaN
            val y = axisY.normalizedOrNull() ?: return Float.NaN
            val z = axisZ.normalizedOrNull() ?: return Float.NaN
            return x cross y dot z
        }

    /** Maximum absolute dot product between normalized basis axes. */
    val orthogonalityError: Float
        get() {
            val x = axisX.normalizedOrNull() ?: return Float.NaN
            val y = axisY.normalizedOrNull() ?: return Float.NaN
            val z = axisZ.normalizedOrNull() ?: return Float.NaN
            return max(abs(x dot y), max(abs(x dot z), abs(y dot z)))
        }

    /** Relative spread of the three axis scales; zero means a uniform similarity transform. */
    val scaleAnisotropy: Float
        get() {
            val mean = uniformScale
            if (!mean.isFinite() || mean <= MINIMUM_SCALE) return Float.NaN
            val maximumDeviation = max(
                abs(scaleX - mean),
                max(abs(scaleY - mean), abs(scaleZ - mean)),
            )
            return maximumDeviation / mean
        }

    val isRightHanded: Boolean
        get() = normalizedDeterminant > 0f

    val isApproximatelyAffineSimilarity: Boolean
        get() = isRightHanded &&
            affineBottomRowError <= MAXIMUM_AFFINE_ROW_ERROR &&
            orthogonalityError <= MAXIMUM_ORTHOGONALITY_ERROR &&
            scaleAnisotropy <= MAXIMUM_SCALE_ANISOTROPY

    /** Euler components for Rz(roll) * Ry(yaw) * Rx(pitch) in metric camera space. */
    val metricYawRadians: Float
        get() {
            val x = axisX.normalizedOrNull() ?: return Float.NaN
            return asin((-x.z).coerceIn(-1f, 1f))
        }

    val metricPitchRadians: Float
        get() {
            val y = axisY.normalizedOrNull() ?: return Float.NaN
            val z = axisZ.normalizedOrNull() ?: return Float.NaN
            return atan2(y.z, z.z)
        }

    val metricRollRadians: Float
        get() {
            val x = axisX.normalizedOrNull() ?: return Float.NaN
            return atan2(x.y, x.x)
        }

    /**
     * Roll of the transformed canonical +X axis in normalized image coordinates.
     * Metric space is Y-up, while normalized image coordinates are Y-down, hence the sign flip.
     */
    val normalizedImageRollRadians: Float
        get() = -metricRollRadians

    fun transformCanonicalPoint(point: FaceTransformVector3): FaceTransformVector3 =
        translation + axisX * point.x + axisY * point.y + axisZ * point.z

    /**
     * Maps a metric-space direction to the same 2D orientation contract used by the camera image.
     * Rotation is applied first, then front-camera mirroring. Fill-centre crop only adds a positive
     * uniform scale and therefore cannot change the returned direction.
     */
    fun mapMetricDirectionToDisplay(
        direction: FaceTransformVector3,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
    ): ImagePlaneDirection {
        val imageX = direction.x
        val imageY = -direction.y
        val normalizedRotation = normalizeQuarterRotation(rotationDegrees)
        val rotated = when (normalizedRotation) {
            0 -> ImagePlaneDirection(imageX, imageY)
            90 -> ImagePlaneDirection(-imageY, imageX)
            180 -> ImagePlaneDirection(-imageX, -imageY)
            270 -> ImagePlaneDirection(imageY, -imageX)
            else -> error("Unsupported normalized rotation: $normalizedRotation")
        }
        return if (mirrorHorizontal) {
            ImagePlaneDirection(-rotated.x, rotated.y)
        } else {
            rotated
        }
    }

    companion object {
        const val MATRIX_SIZE = 16

        fun fromColumnMajor(values: FloatArray): CanonicalFaceTransform? {
            if (values.size != MATRIX_SIZE || values.any { !it.isFinite() }) return null
            return CanonicalFaceTransform(
                axisX = FaceTransformVector3(values[0], values[1], values[2]),
                axisY = FaceTransformVector3(values[4], values[5], values[6]),
                axisZ = FaceTransformVector3(values[8], values[9], values[10]),
                translation = FaceTransformVector3(values[12], values[13], values[14]),
                affineBottomRowError = max(
                    max(abs(values[3]), abs(values[7])),
                    max(abs(values[11]), abs(values[15] - 1f)),
                ),
            )
        }

        private fun normalizeQuarterRotation(rotationDegrees: Int): Int {
            val normalized = ((rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION
            require(normalized % QUARTER_ROTATION == 0) {
                "Rotation must be a multiple of 90 degrees"
            }
            return normalized
        }

        private const val AXIS_COUNT = 3f
        private const val MINIMUM_SCALE = 1e-6f
        private const val MAXIMUM_AFFINE_ROW_ERROR = 1e-3f
        private const val MAXIMUM_ORTHOGONALITY_ERROR = 2e-3f
        private const val MAXIMUM_SCALE_ANISOTROPY = 2e-3f
        private const val QUARTER_ROTATION = 90
        private const val FULL_ROTATION = 360
    }
}
