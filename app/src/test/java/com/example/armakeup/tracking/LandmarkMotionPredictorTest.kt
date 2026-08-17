package com.example.armakeup.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkMotionPredictorTest {

    @Test
    fun firstMeasurementStartsWithoutInventedMotion() {
        val predictor = predictor(renderLeadMs = 8L)

        val frame = predictor.update(point(0.2f, 0.4f), timestampMs = 1_000L)
        val predictionSeconds = frame.predictionSeconds(1_000L)

        assertEquals(0.2f, frame.x(0, predictionSeconds), EPSILON)
        assertEquals(0.4f, frame.y(0, predictionSeconds), EPSILON)
        assertFalse(frame.predictedOnly)
    }

    @Test
    fun constantMotionIsExtrapolatedTowardRenderTimestamp() {
        val predictor = predictor(renderLeadMs = 0L)
        predictor.update(point(0.20f, 0.4f), timestampMs = 1_000L)
        val frame = predictor.update(point(0.24f, 0.4f), timestampMs = 1_040L)

        val atMeasurement = frame.x(0, frame.predictionSeconds(1_040L))
        val atRender = frame.x(0, frame.predictionSeconds(1_080L))

        assertTrue(atRender > atMeasurement)
    }

    @Test
    fun extrapolationStopsAtConfiguredHorizon() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(point(0.20f, 0.4f), timestampMs = 1_000L)
        val frame = predictor.update(point(0.24f, 0.4f), timestampMs = 1_040L)

        val atCap = frame.x(0, frame.predictionSeconds(1_090L))
        val longAfterCap = frame.x(0, frame.predictionSeconds(2_000L))

        assertEquals(atCap, longAfterCap, EPSILON)
        assertFalse(frame.shouldAnimate(1_090L))
    }

    @Test
    fun shortDetectionDropoutKeepsPredictedFrame() {
        val predictor = predictor(maxPredictionMs = 50L, holdAfterLossMs = 100L)
        predictor.update(point(0.2f, 0.4f), timestampMs = 1_000L)

        val heldFrame = predictor.predictWithoutMeasurement(1_099L)

        assertNotNull(heldFrame)
        assertTrue(heldFrame!!.predictedOnly)
        assertNull(predictor.predictWithoutMeasurement(1_101L))
    }

    @Test
    fun firstMeasurementAfterDropoutResetsStaleVelocity() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        predictor.update(face(translationX = 0.03f), timestampMs = 1_040L)
        predictor.update(face(translationX = 0.06f), timestampMs = 1_080L)
        predictor.update(face(translationX = 0.09f), timestampMs = 1_120L)
        assertNotNull(predictor.predictWithoutMeasurement(1_140L))

        val reacquired = predictor.update(face(translationX = -0.06f), timestampMs = 1_160L)
        val atMeasurement = reacquired.x(0, reacquired.predictionSeconds(1_160L))
        val afterPrediction = reacquired.x(0, reacquired.predictionSeconds(1_210L))

        assertEquals(atMeasurement, afterPrediction, EPSILON)
        assertEquals(FACE_X_COORDINATES[0] - 0.06f, atMeasurement, EPSILON)
    }

    @Test
    fun implausibleJumpResetsVelocityInsteadOfOvershooting() {
        val predictor = predictor(renderLeadMs = 0L, maxCentroidJump = 0.2f)
        predictor.update(point(0.10f, 0.4f), timestampMs = 1_000L)
        predictor.update(point(0.12f, 0.4f), timestampMs = 1_040L)

        val reacquired = predictor.update(point(0.90f, 0.4f), timestampMs = 1_080L)
        val later = reacquired.x(0, reacquired.predictionSeconds(1_120L))

        assertEquals(0.90f, later, EPSILON)
    }

    @Test
    fun smallStationaryJitterIsAttenuated() {
        val predictor = predictor(renderLeadMs = 0L)
        predictor.update(point(0.500f, 0.4f), timestampMs = 1_000L)

        val frame = predictor.update(point(0.501f, 0.4f), timestampMs = 1_040L)
        val filteredX = frame.x(0, frame.predictionSeconds(1_040L))

        assertTrue(filteredX > 0.500f)
        assertTrue(filteredX < 0.501f)
    }

    @Test
    fun velocityDeadZonePreventsStationaryNoiseFromDrifting() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(point(0.5000f, 0.4f), timestampMs = 1_000L)
        val frame = predictor.update(point(0.5001f, 0.4f), timestampMs = 1_040L)

        val atMeasurement = frame.x(0, frame.predictionSeconds(1_040L))
        val afterPrediction = frame.x(0, frame.predictionSeconds(1_090L))

        assertEquals(atMeasurement, afterPrediction, EPSILON)
    }

    @Test
    fun firstAbruptMotionIsFilteredBeforeItBecomesTrusted() {
        val predictor = predictor(renderLeadMs = 0L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)

        val frame = predictor.update(face(translationX = 0.02f), timestampMs = 1_040L)
        val predictionSeconds = frame.predictionSeconds(1_040L)
        val filteredTranslation = frame.x(0, predictionSeconds) - FACE_X_COORDINATES[0]

        assertTrue(filteredTranslation > 0.009f)
        assertTrue(filteredTranslation < 0.014f)
    }

    @Test
    fun firstAbruptMotionGetsOnlyCandidatePredictionGain() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        val frame = predictor.update(face(translationX = 0.02f), timestampMs = 1_040L)

        val atMeasurement = frame.x(0, frame.predictionSeconds(1_040L))
        val atRender = frame.x(0, frame.predictionSeconds(1_080L))

        assertTrue(atRender - atMeasurement > 0.003f)
        assertTrue(atRender - atMeasurement < 0.008f)
    }

    @Test
    fun coherentVelocityDecaysQuicklyWhenCameraStops() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        predictor.update(face(translationX = 0.02f), timestampMs = 1_040L)
        val stopped = predictor.update(face(translationX = 0.02f), timestampMs = 1_080L)

        val atStop = stopped.x(0, stopped.predictionSeconds(1_080L))
        val afterPrediction = stopped.x(0, stopped.predictionSeconds(1_130L))

        assertEquals(atStop, afterPrediction, EPSILON)
    }

    @Test
    fun slowMotionIsConservativeUntilDirectionIsConfirmed() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        val frame = predictor.update(face(translationX = 0.004f), timestampMs = 1_040L)

        val renderedTranslation = frame.x(0, frame.predictionSeconds(1_080L)) -
            FACE_X_COORDINATES[0]

        assertTrue(renderedTranslation > 0.002f)
        assertTrue(renderedTranslation < 0.004f)
    }

    @Test
    fun coherentMotionReachesFullPredictionAfterConfirmation() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        predictor.update(face(translationX = 0.01f), timestampMs = 1_040L)
        predictor.update(face(translationX = 0.02f), timestampMs = 1_080L)
        val confirmed = predictor.update(face(translationX = 0.03f), timestampMs = 1_120L)

        val atMeasurement = confirmed.x(0, confirmed.predictionSeconds(1_120L))
        val atRender = confirmed.x(0, confirmed.predictionSeconds(1_160L))

        assertTrue(atRender - atMeasurement > 0.008f)
    }

    @Test
    fun coherentMotionPredictionGainDoesNotJumpAtConfirmationBoundary() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)

        val predictionDistances = (1..5).map { frameIndex ->
            val timestampMs = 1_000L + frameIndex * 40L
            val frame = predictor.update(
                face(translationX = frameIndex * 0.01f),
                timestampMs = timestampMs,
            )
            predictionDistance(frame, timestampMs, timestampMs + 40L)
        }

        predictionDistances.zipWithNext().forEach { (previous, current) ->
            assertTrue(
                "prediction gain jumped from $previous to $current",
                current - previous <= 0.0041f,
            )
        }
        assertTrue(predictionDistances.last() > predictionDistances.first())
    }

    @Test
    fun oneNoisyDirectionSampleDoesNotCollapseConfirmedPrediction() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        predictor.update(face(translationX = 0.01f), timestampMs = 1_040L)
        predictor.update(face(translationX = 0.02f), timestampMs = 1_080L)
        val confirmed = predictor.update(face(translationX = 0.03f), timestampMs = 1_120L)
        val confirmedLead = predictionDistance(confirmed, 1_120L, 1_160L)

        val noisyDirection = predictor.update(
            face(translationX = 0.03f, translationY = 0.01f),
            timestampMs = 1_160L,
        )
        val noisyLead = predictionDistance(noisyDirection, 1_160L, 1_200L)

        assertTrue(
            "one direction outlier collapsed prediction from $confirmedLead to $noisyLead",
            noisyLead >= confirmedLead * 0.5f,
        )
    }

    @Test
    fun rigidCameraRotationAlsoRampsPredictionWithoutAConfirmationJump() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        val base = trackedFace()
        predictor.update(base, timestampMs = 1_000L)

        val predictionDistances = (1..5).map { frameIndex ->
            val timestampMs = 1_000L + frameIndex * 40L
            val frame = predictor.update(
                similarityTransform(base, rotationRadians = frameIndex * 0.04f),
                timestampMs = timestampMs,
            )
            predictionDistance(
                frame = frame,
                measurementTimestampMs = timestampMs,
                renderTimestampMs = timestampMs + 40L,
                landmarkIndex = 234,
            )
        }

        predictionDistances.zipWithNext().forEach { (previous, current) ->
            assertTrue(
                "rotation prediction jumped from $previous to $current",
                current - previous <= 0.0025f,
            )
        }
    }

    @Test
    fun oneCorruptedPoseAnchorDoesNotDeflectLipPrediction() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        val base = trackedFace()
        predictor.update(base, timestampMs = 1_000L)
        predictor.update(
            similarityTransform(base, rotationRadians = 0f, translationX = 0.01f),
            timestampMs = 1_040L,
        )
        predictor.update(
            similarityTransform(base, rotationRadians = 0f, translationX = 0.02f),
            timestampMs = 1_080L,
        )
        val stable = predictor.update(
            similarityTransform(base, rotationRadians = 0f, translationX = 0.03f),
            timestampMs = 1_120L,
        )
        val stableDelta = predictionDelta(stable, 1_120L, 1_160L, landmarkIndex = 61)

        val corrupted = similarityTransform(
            base,
            rotationRadians = 0f,
            translationX = 0.04f,
        ).also { coordinates ->
            coordinates[33 * 3] += 0.08f
            coordinates[33 * 3 + 1] -= 0.05f
        }
        val noisy = predictor.update(corrupted, timestampMs = 1_160L)
        val noisyDelta = predictionDelta(noisy, 1_160L, 1_200L, landmarkIndex = 61)

        assertTrue("prediction reversed after one anchor outlier", noisyDelta.first > 0f)
        assertTrue(
            "anchor outlier injected vertical prediction ${noisyDelta.second}",
            kotlin.math.abs(noisyDelta.second) <= 0.002f,
        )
        assertTrue(
            "anchor outlier changed lead from ${stableDelta.first} to ${noisyDelta.first}",
            kotlin.math.abs(noisyDelta.first - stableDelta.first) <= 0.004f,
        )
    }

    @Test
    fun abruptDirectionChangeCannotCarryOldVelocityForward() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        predictor.update(face(translationX = 0.03f), timestampMs = 1_040L)
        predictor.update(face(translationX = 0.06f), timestampMs = 1_080L)
        predictor.update(face(translationX = 0.09f), timestampMs = 1_120L)
        val reversed = predictor.update(face(translationX = 0.06f), timestampMs = 1_160L)

        val atMeasurement = reversed.x(0, reversed.predictionSeconds(1_160L))
        val atRender = reversed.x(0, reversed.predictionSeconds(1_200L))

        assertTrue(atRender <= atMeasurement)
    }

    @Test
    fun alternatingCameraJerkIsNotAmplifiedBeyondMeasuredRange() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        predictor.update(face(translationX = 0f), timestampMs = 1_000L)
        val translations = floatArrayOf(0.03f, 0f, 0.03f, 0f)

        translations.forEachIndexed { index, translation ->
            val timestamp = 1_040L + index * 40L
            val frame = predictor.update(face(translationX = translation), timestamp)
            val renderedTranslation = frame.x(0, frame.predictionSeconds(timestamp + 40L)) -
                FACE_X_COORDINATES[0]
            assertTrue(renderedTranslation >= -EPSILON)
            assertTrue(renderedTranslation <= 0.03f + EPSILON)
        }
    }

    @Test
    fun lipOnlyDeformationDoesNotCreateGlobalPrediction() {
        val predictor = predictor(renderLeadMs = 0L, maxPredictionMs = 50L)
        val base = trackedFace()
        predictor.update(base, timestampMs = 1_000L)
        val deformed = base.copyOf().also { coordinates ->
            com.example.armakeup.makeup.LipLandmarkTopology.outerContour.forEach { index ->
                coordinates[index * 3] += 0.02f
            }
            com.example.armakeup.makeup.LipLandmarkTopology.innerContour.forEach { index ->
                coordinates[index * 3] += 0.02f
            }
        }
        val frame = predictor.update(deformed, timestampMs = 1_040L)

        frame.copyVelocities().forEach { velocity ->
            assertEquals(0f, velocity, EPSILON)
        }
    }

    private fun predictor(
        renderLeadMs: Long = 4L,
        maxPredictionMs: Long = 45L,
        holdAfterLossMs: Long = 120L,
        maxCentroidJump: Float = 0.25f,
    ) = LandmarkMotionPredictor(
        renderLeadMs = renderLeadMs,
        maxPredictionMs = maxPredictionMs,
        holdAfterLossMs = holdAfterLossMs,
        maxCentroidJump = maxCentroidJump,
    )

    private fun point(x: Float, y: Float, z: Float = 0f): FloatArray =
        floatArrayOf(x, y, z)

    private fun face(translationX: Float, translationY: Float = 0f): FloatArray =
        FloatArray(FACE_X_COORDINATES.size * 3).also { coordinates ->
            FACE_X_COORDINATES.forEachIndexed { index, x ->
                coordinates[index * 3] = x + translationX
                coordinates[index * 3 + 1] = 0.3f + index * 0.1f + translationY
            }
        }

    private fun predictionDistance(
        frame: LandmarkRenderFrame,
        measurementTimestampMs: Long,
        renderTimestampMs: Long,
        landmarkIndex: Int = 0,
    ): Float {
        val measurementSeconds = frame.predictionSeconds(measurementTimestampMs)
        val renderSeconds = frame.predictionSeconds(renderTimestampMs)
        val deltaX = frame.x(landmarkIndex, renderSeconds) -
            frame.x(landmarkIndex, measurementSeconds)
        val deltaY = frame.y(landmarkIndex, renderSeconds) -
            frame.y(landmarkIndex, measurementSeconds)
        return kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    private fun predictionDelta(
        frame: LandmarkRenderFrame,
        measurementTimestampMs: Long,
        renderTimestampMs: Long,
        landmarkIndex: Int,
    ): Pair<Float, Float> {
        val measurementSeconds = frame.predictionSeconds(measurementTimestampMs)
        val renderSeconds = frame.predictionSeconds(renderTimestampMs)
        return Pair(
            frame.x(landmarkIndex, renderSeconds) -
                frame.x(landmarkIndex, measurementSeconds),
            frame.y(landmarkIndex, renderSeconds) -
                frame.y(landmarkIndex, measurementSeconds),
        )
    }

    private fun similarityTransform(
        coordinates: FloatArray,
        rotationRadians: Float,
        scale: Float = 1f,
        translationX: Float = 0f,
        translationY: Float = 0f,
    ): FloatArray {
        val cosRotation = kotlin.math.cos(rotationRadians)
        val sinRotation = kotlin.math.sin(rotationRadians)
        return coordinates.copyOf().also { transformed ->
            var index = 0
            while (index < transformed.size) {
                val localX = coordinates[index] - 0.5f
                val localY = coordinates[index + 1] - 0.5f
                transformed[index] = 0.5f + translationX + scale * (
                    cosRotation * localX - sinRotation * localY
                )
                transformed[index + 1] = 0.5f + translationY + scale * (
                    sinRotation * localX + cosRotation * localY
                )
                index += 3
            }
        }
    }

    private fun trackedFace(): FloatArray = FloatArray(478 * 3).also { coordinates ->
        repeat(478) { index ->
            coordinates[index * 3] = 0.5f + (index % 11 - 5) * 0.002f
            coordinates[index * 3 + 1] = 0.5f + (index % 13 - 6) * 0.002f
        }
        setPoint(coordinates, 33, 0.40f, 0.42f)
        setPoint(coordinates, 133, 0.44f, 0.42f)
        setPoint(coordinates, 362, 0.56f, 0.42f)
        setPoint(coordinates, 263, 0.60f, 0.42f)
        setPoint(coordinates, 168, 0.50f, 0.48f)
        setPoint(coordinates, 1, 0.50f, 0.54f)
        setPoint(coordinates, 234, 0.34f, 0.52f)
        setPoint(coordinates, 454, 0.66f, 0.52f)
    }

    private fun setPoint(
        coordinates: FloatArray,
        index: Int,
        x: Float,
        y: Float,
    ) {
        coordinates[index * 3] = x
        coordinates[index * 3 + 1] = y
    }

    companion object {
        private const val EPSILON = 0.0001f
        private val FACE_X_COORDINATES = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
    }
}
