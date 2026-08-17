package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalFaceTransformTest {

    @Test
    fun parsesMediaPipeColumnMajorBasisAndTranslation() {
        val matrix = floatArrayOf(
            2f, 0f, 0f, 0f,
            0f, 2f, 0f, 0f,
            0f, 0f, 2f, 0f,
            3f, 4f, -50f, 1f,
        )

        val transform = requireNotNull(CanonicalFaceTransform.fromColumnMajor(matrix))

        assertEquals(FaceTransformVector3(2f, 0f, 0f), transform.axisX)
        assertEquals(FaceTransformVector3(0f, 2f, 0f), transform.axisY)
        assertEquals(FaceTransformVector3(0f, 0f, 2f), transform.axisZ)
        assertEquals(FaceTransformVector3(3f, 4f, -50f), transform.translation)
        assertEquals(2f, transform.uniformScale, EPSILON)
        assertEquals(1f, transform.normalizedDeterminant, EPSILON)
        assertEquals(0f, transform.orthogonalityError, EPSILON)
        assertEquals(0f, transform.scaleAnisotropy, EPSILON)
        assertTrue(transform.isApproximatelyAffineSimilarity)
    }

    @Test
    fun transformsCanonicalPointUsingColumnVectors() {
        val transform = requireNotNull(
            CanonicalFaceTransform.fromColumnMajor(
                floatArrayOf(
                    2f, 0f, 0f, 0f,
                    0f, 3f, 0f, 0f,
                    0f, 0f, 4f, 0f,
                    10f, 20f, 30f, 1f,
                ),
            ),
        )

        assertEquals(
            FaceTransformVector3(12f, 26f, 42f),
            transform.transformCanonicalPoint(FaceTransformVector3(1f, 2f, 3f)),
        )
    }

    @Test
    fun rejectsInvalidMatrixSizeAndNonFiniteValues() {
        assertNull(CanonicalFaceTransform.fromColumnMajor(FloatArray(15)))
        assertNull(
            CanonicalFaceTransform.fromColumnMajor(
                FloatArray(16) { index -> if (index == 5) Float.NaN else 0f },
            ),
        )
    }

    @Test
    fun detectsReflectionNonAffineRowAndAnisotropicScale() {
        val reflected = requireNotNull(
            CanonicalFaceTransform.fromColumnMajor(
                floatArrayOf(
                    -1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, -40f, 1f,
                ),
            ),
        )
        val nonAffine = requireNotNull(
            CanonicalFaceTransform.fromColumnMajor(
                floatArrayOf(
                    1f, 0f, 0f, 0.01f,
                    0f, 2f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, -40f, 1f,
                ),
            ),
        )

        assertFalse(reflected.isRightHanded)
        assertEquals(-1f, reflected.normalizedDeterminant, EPSILON)
        assertFalse(reflected.isApproximatelyAffineSimilarity)
        assertEquals(0.01f, nonAffine.affineBottomRowError, EPSILON)
        assertTrue(nonAffine.scaleAnisotropy > 0.1f)
        assertFalse(nonAffine.isApproximatelyAffineSimilarity)
    }

    @Test
    fun convertsMetricYUpDirectionToImageYDown() {
        val transform = requireNotNull(CanonicalFaceTransform.fromColumnMajor(identityMatrix()))

        val imageDirection = transform.mapMetricDirectionToDisplay(
            direction = FaceTransformVector3(1f, 1f, 0f),
            rotationDegrees = 0,
            mirrorHorizontal = false,
        )

        assertEquals(1f, imageDirection.x, EPSILON)
        assertEquals(-1f, imageDirection.y, EPSILON)
    }

    @Test
    fun appliesQuarterRotationThenFrontCameraMirror() {
        val transform = requireNotNull(CanonicalFaceTransform.fromColumnMajor(identityMatrix()))

        val rotated = transform.mapMetricDirectionToDisplay(
            direction = FaceTransformVector3(1f, 0f, 0f),
            rotationDegrees = 90,
            mirrorHorizontal = false,
        )
        val mirrored = transform.mapMetricDirectionToDisplay(
            direction = FaceTransformVector3(1f, 0f, 0f),
            rotationDegrees = 90,
            mirrorHorizontal = true,
        )

        assertEquals(0f, rotated.x, EPSILON)
        assertEquals(1f, rotated.y, EPSILON)
        assertEquals(0f, mirrored.x, EPSILON)
        assertEquals(1f, mirrored.y, EPSILON)

        val horizontalMirror = transform.mapMetricDirectionToDisplay(
            direction = FaceTransformVector3(1f, 0f, 0f),
            rotationDegrees = 0,
            mirrorHorizontal = true,
        )
        assertEquals(-1f, horizontalMirror.x, EPSILON)
        assertEquals(0f, horizontalMirror.y, EPSILON)
    }

    @Test
    fun fillCenterCropPreservesMappedDirection() {
        val transform = requireNotNull(CanonicalFaceTransform.fromColumnMajor(identityMatrix()))
        val direction = transform.mapMetricDirectionToDisplay(
            direction = FaceTransformVector3(0.25f, -0.5f, 0f),
            rotationDegrees = 270,
            mirrorHorizontal = true,
        )
        val crop = FillCenterTransform.calculate(
            viewWidth = 1080,
            viewHeight = 1920,
            sourceWidth = 1440,
            sourceHeight = 1080,
        )
        val startX = 0.5f
        val startY = 0.5f
        val directionScale = 0.1f
        val pixelDeltaX = crop.mapX(
            startX + direction.x * directionScale,
            sourceWidth = 1440,
        ) - crop.mapX(startX, sourceWidth = 1440)
        val pixelDeltaY = crop.mapY(
            startY + direction.y * directionScale,
            sourceHeight = 1080,
        ) - crop.mapY(startY, sourceHeight = 1080)

        assertTrue(crop.scale > 0f)
        assertEquals(direction.x * directionScale * 1440f * crop.scale, pixelDeltaX, EPSILON)
        assertEquals(direction.y * directionScale * 1080f * crop.scale, pixelDeltaY, EPSILON)
    }

    @Test
    fun rejectsNonQuarterDisplayRotation() {
        val transform = requireNotNull(CanonicalFaceTransform.fromColumnMajor(identityMatrix()))

        assertThrows(IllegalArgumentException::class.java) {
            transform.mapMetricDirectionToDisplay(
                direction = transform.axisX,
                rotationDegrees = 45,
                mirrorHorizontal = false,
            )
        }
    }

    @Test
    fun identityHasUsableRollAndBasis() {
        val transform = CanonicalFaceTransform.fromColumnMajor(identityMatrix())

        assertNotNull(transform)
        assertEquals(0f, requireNotNull(transform).normalizedImageRollRadians, EPSILON)
    }

    @Test
    fun extractsYawPitchAndRollUsingDocumentedMetricAxes() {
        val yaw = Math.toRadians(30.0).toFloat()
        val pitch = Math.toRadians(20.0).toFloat()
        val roll = Math.toRadians(-15.0).toFloat()

        val yawTransform = requireNotNull(
            CanonicalFaceTransform.fromColumnMajor(rotationY(yaw)),
        )
        val pitchTransform = requireNotNull(
            CanonicalFaceTransform.fromColumnMajor(rotationX(pitch)),
        )
        val rollTransform = requireNotNull(
            CanonicalFaceTransform.fromColumnMajor(rotationZ(roll)),
        )

        assertEquals(yaw, yawTransform.metricYawRadians, EPSILON)
        assertEquals(pitch, pitchTransform.metricPitchRadians, EPSILON)
        assertEquals(roll, rollTransform.metricRollRadians, EPSILON)
        assertEquals(-roll, rollTransform.normalizedImageRollRadians, EPSILON)
    }

    private fun rotationX(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, cosine, sine, 0f,
            0f, -sine, cosine, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun rotationY(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            cosine, 0f, -sine, 0f,
            0f, 1f, 0f, 0f,
            sine, 0f, cosine, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun rotationZ(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            cosine, sine, 0f, 0f,
            -sine, cosine, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun identityMatrix(): FloatArray = FloatArray(16) { index ->
        if (index % 5 == 0) 1f else 0f
    }

    companion object {
        private const val EPSILON = 1e-5f
    }
}
