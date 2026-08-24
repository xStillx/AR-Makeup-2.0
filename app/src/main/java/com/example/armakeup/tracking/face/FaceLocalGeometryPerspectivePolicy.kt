package com.example.armakeup.tracking.face

import com.example.armakeup.tracking.CanonicalFaceTransform
import kotlin.math.abs

/**
 * Reduces stale 2D local deformation as the face approaches a profile view.
 *
 * ARCore's current projected 3D mesh is the safer source at large yaw/pitch angles because one
 * screen-space affine transform cannot reproduce perspective across a non-planar face.
 */
class FaceLocalGeometryPerspectivePolicy(
    private val fullLocalYawRadians: Float,
    private val globalOnlyYawRadians: Float,
    private val fullLocalPitchRadians: Float,
    private val globalOnlyPitchRadians: Float,
) {
    init {
        require(fullLocalYawRadians.isFinite() && fullLocalYawRadians >= 0f)
        require(globalOnlyYawRadians.isFinite() &&
            globalOnlyYawRadians > fullLocalYawRadians)
        require(fullLocalPitchRadians.isFinite() && fullLocalPitchRadians >= 0f)
        require(globalOnlyPitchRadians.isFinite() &&
            globalOnlyPitchRadians > fullLocalPitchRadians)
    }

    fun localWeight(observation: FaceObservation): Float {
        val pose = observation.pose ?: return 0f
        val cameraFrame = observation.cameraFrame ?: return 0f
        val cameraFromFace = multiplyColumnMajor(
            left = cameraFrame.cameraFromWorld,
            right = pose.faceToWorld,
        )
        val transform = CanonicalFaceTransform.fromColumnMajor(cameraFromFace) ?: return 0f
        val yawWeight = decreasingSmoothstep(
            value = abs(transform.metricYawRadians),
            fullWeightUntil = fullLocalYawRadians,
            zeroWeightAt = globalOnlyYawRadians,
        )
        val pitchWeight = decreasingSmoothstep(
            value = abs(transform.metricPitchRadians),
            fullWeightUntil = fullLocalPitchRadians,
            zeroWeightAt = globalOnlyPitchRadians,
        )
        return minOf(yawWeight, pitchWeight)
    }

    private fun decreasingSmoothstep(
        value: Float,
        fullWeightUntil: Float,
        zeroWeightAt: Float,
    ): Float {
        if (!value.isFinite()) return 0f
        val normalized = ((value - fullWeightUntil) /
            (zeroWeightAt - fullWeightUntil)).coerceIn(0f, 1f)
        val smooth = normalized * normalized * (3f - 2f * normalized)
        return 1f - smooth
    }

    private fun multiplyColumnMajor(left: FaceMatrix4, right: FaceMatrix4): FloatArray {
        val output = FloatArray(FaceMatrix4.ELEMENT_COUNT)
        for (column in 0 until MATRIX_DIMENSION) {
            for (row in 0 until MATRIX_DIMENSION) {
                var value = 0f
                for (component in 0 until MATRIX_DIMENSION) {
                    value += left[component * MATRIX_DIMENSION + row] *
                        right[column * MATRIX_DIMENSION + component]
                }
                output[column * MATRIX_DIMENSION + row] = value
            }
        }
        return output
    }

    companion object {
        val UNRESTRICTED = FaceLocalGeometryPerspectivePolicy(
            fullLocalYawRadians = 0f,
            globalOnlyYawRadians = Float.MAX_VALUE,
            fullLocalPitchRadians = 0f,
            globalOnlyPitchRadians = Float.MAX_VALUE,
        )

        fun profileSafe(): FaceLocalGeometryPerspectivePolicy = FaceLocalGeometryPerspectivePolicy(
            fullLocalYawRadians = Math.toRadians(FULL_LOCAL_YAW_DEGREES).toFloat(),
            globalOnlyYawRadians = Math.toRadians(GLOBAL_ONLY_YAW_DEGREES).toFloat(),
            fullLocalPitchRadians = Math.toRadians(FULL_LOCAL_PITCH_DEGREES).toFloat(),
            globalOnlyPitchRadians = Math.toRadians(GLOBAL_ONLY_PITCH_DEGREES).toFloat(),
        )

        private const val MATRIX_DIMENSION = 4
        private const val FULL_LOCAL_YAW_DEGREES = 32.0
        private const val GLOBAL_ONLY_YAW_DEGREES = 52.0
        private const val FULL_LOCAL_PITCH_DEGREES = 24.0
        private const val GLOBAL_ONLY_PITCH_DEGREES = 40.0
    }
}
