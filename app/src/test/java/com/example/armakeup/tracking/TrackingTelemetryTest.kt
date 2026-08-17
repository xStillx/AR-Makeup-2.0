package com.example.armakeup.tracking

import com.example.armakeup.makeup.LipLandmarkTopology
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTelemetryTest {
    @Test
    fun codecRoundTripPreservesOrderedMeasurementAndRenderEvents() {
        val measurement = measurementSample(
            timestampMs = 1_000L,
            raw = faceCoordinates(translationX = 0.01f),
            filtered = faceCoordinates(translationX = 0.005f),
        ).copy(
            captureIntervalMs = 34L,
            frameQuality = TrackingFrameQuality(
                meanLuma = 0.4f,
                lumaStandardDeviation = 0.2f,
                meanGradient = 0.1f,
                exposureTimeNs = 8_000_000L,
                sensitivityIso = 320,
                frameDurationNs = 33_333_333L,
                rollingShutterSkewNs = 12_000_000L,
                aeState = 2,
            ),
            poseFitQuality = TrackingPoseFitQuality(0.02f, 0.9f, 0.8f),
            deviceState = TrackingDeviceState(thermalStatus = 1, 31.5f),
        )
        val render = TrackingRenderSample(
            renderTimestampMs = 1_110L,
            measurementTimestampMs = 1_000L,
            sensorTimestampNs = 999_000_000L,
            predictionSeconds = 0.042f,
            viewportWidth = 1080,
            viewportHeight = 2400,
            lipVisible = true,
            outerLipPoints = floatArrayOf(0.4f, 0.5f, 0.6f, 0.5f),
            innerLipPoints = floatArrayOf(0.45f, 0.5f, 0.55f, 0.5f),
            lipstickFinish = "GLOSS",
            materialCameraCoherence = 0.42f,
            materialMotionSpeed = 0.8f,
            materialTemporalMismatchMs = 37f,
            frameSubmissionCpuMs = 4.5f,
            filamentFrameRendered = false,
            gyroscopeApplied = true,
            gyroscopeIntervalMs = 42f,
            gyroscopeRotationX = 0.01f,
            gyroscopeRotationY = -0.02f,
            gyroscopeRotationZ = 0.03f,
            gyroscopeTranslationX = 0.04f,
            gyroscopeTranslationY = -0.05f,
            gyroscopeRollRadians = -0.03f,
            cameraMotionPredictionSeconds = 0.011f,
            globalPredictionCoverage = 0.25f,
        )
        val bytes = ByteArrayOutputStream().also { output ->
            TrackingTelemetryCodec.writeHeader(
                output,
                TrackingTelemetryHeader("stationary", startedAtEpochMs = 42L),
            )
            TrackingTelemetryCodec.writeEvent(output, measurement)
            TrackingTelemetryCodec.writeEvent(output, render)
            TrackingTelemetryCodec.writeFooter(output, droppedEventCount = 3L)
        }.toByteArray()

        val decoded = TrackingTelemetryCodec.read(ByteArrayInputStream(bytes))

        assertEquals("stationary", decoded.header.scenario)
        assertEquals(2, decoded.events.size)
        assertEquals(3L, decoded.droppedEventCount)
        val decodedMeasurement = decoded.events[0] as TrackingMeasurementSample
        assertArrayEquals(measurement.rawLandmarks, decodedMeasurement.rawLandmarks, 0f)
        assertArrayEquals(
            measurement.filteredGeometry.localLipCoordinates,
            decodedMeasurement.filteredGeometry.localLipCoordinates,
            0f,
        )
        assertEquals(measurement.captureIntervalMs, decodedMeasurement.captureIntervalMs)
        assertEquals(measurement.frameQuality, decodedMeasurement.frameQuality)
        assertEquals(measurement.poseFitQuality, decodedMeasurement.poseFitQuality)
        assertEquals(measurement.deviceState, decodedMeasurement.deviceState)
        val decodedRender = decoded.events[1] as TrackingRenderSample
        assertArrayEquals(render.outerLipPoints, decodedRender.outerLipPoints, 0f)
        assertEquals(render.lipstickFinish, decodedRender.lipstickFinish)
        assertEquals(render.materialCameraCoherence, decodedRender.materialCameraCoherence, 0f)
        assertEquals(render.materialMotionSpeed, decodedRender.materialMotionSpeed, 0f)
        assertEquals(
            render.materialTemporalMismatchMs,
            decodedRender.materialTemporalMismatchMs,
            0f,
        )
        assertEquals(render.frameSubmissionCpuMs, decodedRender.frameSubmissionCpuMs, 0f)
        assertEquals(render.filamentFrameRendered, decodedRender.filamentFrameRendered)
        assertEquals(render.gyroscopeApplied, decodedRender.gyroscopeApplied)
        assertEquals(render.gyroscopeIntervalMs, decodedRender.gyroscopeIntervalMs, 0f)
        assertEquals(render.gyroscopeRotationY, decodedRender.gyroscopeRotationY, 0f)
        assertEquals(render.gyroscopeTranslationX, decodedRender.gyroscopeTranslationX, 0f)
        assertEquals(render.gyroscopeRollRadians, decodedRender.gyroscopeRollRadians, 0f)
        assertEquals(
            render.cameraMotionPredictionSeconds,
            decodedRender.cameraMotionPredictionSeconds,
            0f,
        )
        assertEquals(
            render.globalPredictionCoverage,
            decodedRender.globalPredictionCoverage,
            0f,
        )
    }

    @Test
    fun codecReadsLegacyV1MeasurementWithUnknownInputQuality() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0x41525636)
                writeInt(1)
                writeUTF("legacy")
                writeLong(42L)
                writeByte(1)
                writeLong(1_000L)
                writeLong(999_000_000L)
                writeLong(1_100L)
                writeLong(100L)
                writeFloat(30f)
                writeFloat(Float.NaN)
                writeBoolean(true)
                writeBoolean(false)
                repeat(3) { writeInt(0) }
                repeat(2) {
                    repeat(4) { writeFloat(Float.NaN) }
                    writeInt(0)
                }
                writeByte(0x7f)
                writeLong(0L)
            }
        }.toByteArray()

        val decoded = TrackingTelemetryCodec.read(ByteArrayInputStream(bytes))
        val measurement = decoded.measurements.single()

        assertEquals(-1L, measurement.captureIntervalMs)
        assertTrue(measurement.frameQuality.meanLuma.isNaN())
        assertTrue(measurement.poseFitQuality.quality.isNaN())
        assertEquals(-1, measurement.deviceState.thermalStatus)
    }

    @Test
    fun codecReadsLegacyV2RenderWithUnknownMaterialState() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0x41525636)
                writeInt(2)
                writeUTF("legacy_render")
                writeLong(42L)
                writeByte(2)
                writeLong(1_100L)
                writeLong(1_000L)
                writeLong(999_000_000L)
                writeFloat(0.04f)
                writeInt(1080)
                writeInt(2400)
                writeBoolean(true)
                repeat(2) { writeInt(0) }
                writeByte(0x7f)
                writeLong(0L)
            }
        }.toByteArray()

        val render = TrackingTelemetryCodec.read(ByteArrayInputStream(bytes)).renders.single()

        assertEquals("UNKNOWN", render.lipstickFinish)
        assertTrue(render.materialCameraCoherence.isNaN())
        assertTrue(render.frameSubmissionCpuMs.isNaN())
        assertTrue(render.filamentFrameRendered)
    }

    @Test
    fun codecReadsLegacyV3RenderWithGyroscopeDisabled() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0x41525636)
                writeInt(3)
                writeUTF("legacy_material")
                writeLong(42L)
                writeByte(2)
                writeLong(1_100L)
                writeLong(1_000L)
                writeLong(999_000_000L)
                writeFloat(0.04f)
                writeInt(1080)
                writeInt(2400)
                writeBoolean(true)
                repeat(2) { writeInt(0) }
                writeUTF("SATIN")
                repeat(4) { writeFloat(1f) }
                writeBoolean(true)
                writeByte(0x7f)
                writeLong(0L)
            }
        }.toByteArray()

        val render = TrackingTelemetryCodec.read(ByteArrayInputStream(bytes)).renders.single()

        assertEquals("SATIN", render.lipstickFinish)
        assertFalse(render.gyroscopeApplied)
        assertTrue(render.gyroscopeIntervalMs.isNaN())
        assertEquals(0f, render.gyroscopeTranslationX, 0f)
    }

    @Test
    fun codecReadsLegacyV4RenderWithUnknownPredictionCoverage() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0x41525636)
                writeInt(4)
                writeUTF("legacy_gyro")
                writeLong(42L)
                writeByte(2)
                writeLong(1_100L)
                writeLong(1_000L)
                writeLong(999_000_000L)
                writeFloat(0.04f)
                writeInt(1080)
                writeInt(2400)
                writeBoolean(true)
                repeat(2) { writeInt(0) }
                writeUTF("TRACKING_TEST")
                repeat(4) { writeFloat(1f) }
                writeBoolean(true)
                writeBoolean(true)
                repeat(7) { writeFloat(0.01f) }
                writeByte(0x7f)
                writeLong(0L)
            }
        }.toByteArray()

        val render = TrackingTelemetryCodec.read(ByteArrayInputStream(bytes)).renders.single()

        assertTrue(render.gyroscopeApplied)
        assertTrue(render.cameraMotionPredictionSeconds.isNaN())
        assertTrue(render.globalPredictionCoverage.isNaN())
    }

    @Test
    fun analyzerReportsInputPoseAndThermalQuality() {
        val samples = (0..2).map { index ->
            poseOnlyMeasurement(1_000L + index * 33L, index * 0.01f, index * 0.01f).copy(
                captureIntervalMs = 32L + index,
                frameQuality = TrackingFrameQuality(
                    meanLuma = 0.3f + index * 0.1f,
                    lumaStandardDeviation = 0.1f,
                    meanGradient = 0.05f,
                    exposureTimeNs = (7L + index) * 1_000_000L,
                    sensitivityIso = 100 + index * 100,
                    frameDurationNs = 33_000_000L,
                    rollingShutterSkewNs = 10_000_000L,
                    aeState = 2,
                ),
                poseFitQuality = TrackingPoseFitQuality(0.01f, 0.9f, 0.8f),
                deviceState = TrackingDeviceState(index, 30f + index),
            )
        }

        val metrics = TrackingTelemetryAnalyzer.analyze(
            TrackingTelemetrySession(TrackingTelemetryHeader("quality", 0L), samples, 0L),
        ).inputQuality

        assertEquals(33f, metrics.medianCaptureIntervalMs, EPSILON)
        assertEquals(0.4f, metrics.medianMeanLuma, EPSILON)
        assertEquals(8f, metrics.medianExposureMs, EPSILON)
        assertEquals(200f, metrics.medianSensitivityIso, EPSILON)
        assertEquals(0.8f, metrics.medianPoseFitQuality, EPSILON)
        assertEquals(2, metrics.maximumThermalStatus)
        assertEquals(31f, metrics.medianBatteryTemperatureCelsius, EPSILON)
    }

    @Test
    fun analyzerReportsFinishCoherenceAndFrameSubmissionTiming() {
        val renders = listOf(
            renderSample(1_100L, 0f).copy(
                lipstickFinish = "SATIN",
                materialCameraCoherence = 1f,
                materialMotionSpeed = 0.1f,
                materialTemporalMismatchMs = 10f,
                frameSubmissionCpuMs = 2f,
                filamentFrameRendered = true,
                cameraMotionPredictionSeconds = 0f,
                globalPredictionCoverage = 0f,
            ),
            renderSample(1_116L, 0.01f).copy(
                lipstickFinish = "SATIN",
                materialCameraCoherence = 0.4f,
                materialMotionSpeed = 0.8f,
                materialTemporalMismatchMs = 40f,
                frameSubmissionCpuMs = 6f,
                filamentFrameRendered = false,
                gyroscopeApplied = true,
                gyroscopeIntervalMs = 40f,
                gyroscopeRotationX = 0.01f,
                gyroscopeTranslationX = 0.02f,
                cameraMotionPredictionSeconds = 0.008f,
                globalPredictionCoverage = 0.2f,
            ),
            renderSample(1_132L, 0.02f).copy(
                lipstickFinish = "GLOSS",
                materialCameraCoherence = 0.2f,
                materialMotionSpeed = 1.0f,
                materialTemporalMismatchMs = 50f,
                frameSubmissionCpuMs = 4f,
                filamentFrameRendered = true,
                gyroscopeApplied = true,
                gyroscopeIntervalMs = 50f,
                gyroscopeRotationY = 0.02f,
                gyroscopeTranslationY = 0.04f,
                cameraMotionPredictionSeconds = 0.04f,
                globalPredictionCoverage = 0.8f,
            ),
        )

        val metrics = TrackingTelemetryAnalyzer.analyze(
            TrackingTelemetrySession(
                TrackingTelemetryHeader("material", 0L),
                renders,
                droppedEventCount = 0L,
            ),
        ).renderPerformance

        assertEquals(listOf("GLOSS", "SATIN"), metrics.lipstickFinishes)
        assertEquals(4f, metrics.medianFrameSubmissionCpuMs, EPSILON)
        assertEquals(2f / 3f, metrics.filamentRenderedFrameFraction, EPSILON)
        assertEquals(0.4f, metrics.medianMaterialCameraCoherence, EPSILON)
        assertEquals(40f, metrics.p95MaterialTemporalMismatchMs, EPSILON)
        assertEquals(2f / 3f, metrics.gyroscopeAppliedFrameFraction, EPSILON)
        assertEquals(40f, metrics.p95GyroscopeIntervalMs, EPSILON)
        assertEquals(0.02f, metrics.p95GyroscopeTranslation, EPSILON)
        assertEquals(8f, metrics.medianCameraMotionPredictionMs, EPSILON)
        assertEquals(0.2f, metrics.medianGlobalPredictionCoverage, EPSILON)
        assertEquals(2, metrics.byFinish.size)
        assertEquals("GLOSS", metrics.byFinish[0].finish)
        assertEquals(1, metrics.byFinish[0].renderCount)
        assertEquals("SATIN", metrics.byFinish[1].finish)
        assertEquals(2, metrics.byFinish[1].renderCount)
        assertEquals(0.5f, metrics.byFinish[1].filamentRenderedFrameFraction, EPSILON)
    }

    @Test
    fun localLipGeometryIsInvariantToGlobalSimilarityMotion() {
        val original = faceCoordinates(translationX = 0f)
        val transformed = transform(original, scale = 1.4f, rotation = 0.31f, tx = 0.08f, ty = -0.04f)

        val originalGeometry = TrackingGeometryExtractor.extract(original)
        val transformedGeometry = TrackingGeometryExtractor.extract(transformed)

        assertTrue(originalGeometry.pose.isValid)
        assertTrue(transformedGeometry.pose.isValid)
        assertEquals(
            originalGeometry.pose.scale * 1.4f,
            transformedGeometry.pose.scale,
            EPSILON,
        )
        assertEquals(
            originalGeometry.pose.rotationRadians + 0.31f,
            transformedGeometry.pose.rotationRadians,
            EPSILON,
        )
        assertArrayEquals(
            originalGeometry.localLipCoordinates,
            transformedGeometry.localLipCoordinates,
            EPSILON,
        )
    }

    @Test
    fun stationaryReplayReportsProgressivelyLowerVisibleJitter() {
        val events = ArrayList<TrackingTelemetryEvent>()
        repeat(20) { index ->
            val sign = if (index % 2 == 0) 1f else -1f
            val timestampMs = 1_000L + index * 33L
            val raw = faceCoordinates(translationX = sign * 0.010f)
            val filtered = faceCoordinates(translationX = sign * 0.003f)
            events += measurementSample(timestampMs, raw, filtered)
            events += renderSample(timestampMs + 100L, sign * 0.001f)
        }

        val metrics = TrackingTelemetryAnalyzer.analyze(
            TrackingTelemetrySession(
                header = TrackingTelemetryHeader("stationary", 0L),
                events = events,
                droppedEventCount = 0L,
            ),
        )

        assertTrue(metrics.rawLipJitter.rms > metrics.filteredLipJitter.rms)
        assertTrue(metrics.filteredLipJitter.rms > metrics.displayedLipJitter.rms)
        assertEquals(20, metrics.measurementCount)
        assertEquals(20, metrics.renderCount)
    }

    @Test
    fun replayDetectsKnownTwoSampleMotionLag() {
        val path = floatArrayOf(
            0.00f, 0.01f, 0.03f, 0.06f, 0.10f, 0.13f, 0.15f, 0.16f,
            0.14f, 0.11f, 0.07f, 0.04f, 0.02f, 0.01f, 0.00f, -0.01f,
        )
        val events = path.indices.map { index ->
            val delayedIndex = (index - 2).coerceAtLeast(0)
            poseOnlyMeasurement(
                timestampMs = 1_000L + index * 33L,
                rawX = path[index],
                filteredX = path[delayedIndex],
            )
        }

        val metrics = TrackingTelemetryAnalyzer.analyze(
            TrackingTelemetrySession(
                TrackingTelemetryHeader("motion", 0L),
                events,
                droppedEventCount = 0L,
            ),
        )

        assertEquals(66f, metrics.estimatedMotionLagMs!!, EPSILON)
    }

    @Test
    fun replaySelectsStableWindowInsteadOfCountingIntentionalMotionAsJitter() {
        val events = ArrayList<TrackingTelemetryEvent>()
        repeat(150) { index ->
            val timestampMs = 1_000L + index * 33L
            val translation = if (index < 80) {
                index * 0.005f
            } else {
                0.40f + if (index % 2 == 0) 0.0002f else -0.0002f
            }
            val coordinates = faceCoordinates(translation)
            events += measurementSample(timestampMs, coordinates, coordinates)
            events += renderSample(timestampMs + 100L, translation)
        }

        val metrics = TrackingTelemetryAnalyzer.analyze(
            TrackingTelemetrySession(
                TrackingTelemetryHeader("mixed", 0L),
                events,
                droppedEventCount = 0L,
            ),
        )

        assertTrue(metrics.stationaryWindowStartOffsetMs > 2_000L)
        assertTrue(metrics.rawLipJitter.rms < 0.001f)
        assertTrue(metrics.stationaryWindowDurationMs >= 1_900L)
    }

    @Test
    fun replayMeasuresStopOvershootAndReacquisitionJump() {
        val rawPath = floatArrayOf(0f, 0.02f, 0.04f, 0.06f, 0.08f, 0.10f, 0.10f, 0.10f, 0.10f, 0.10f)
        val filteredPath = floatArrayOf(
            0f, 0.018f, 0.038f, 0.058f, 0.078f, 0.098f, 0.115f, 0.108f, 0.102f, 0.10f,
        )
        val events = ArrayList<TrackingTelemetryEvent>()
        rawPath.indices.forEach { index ->
            events += poseOnlyMeasurement(
                timestampMs = 1_000L + index * 33L,
                rawX = rawPath[index],
                filteredX = filteredPath[index],
            )
        }
        events += poseOnlyMeasurement(1_330L, 0.10f, 0.10f).copy(
            facePresent = false,
            rawGeometry = TrackingGeometry.INVALID,
        )
        events += poseOnlyMeasurement(1_363L, 0.20f, 0.20f)

        val metrics = TrackingTelemetryAnalyzer.analyze(
            TrackingTelemetrySession(
                TrackingTelemetryHeader("stop_dropout", 0L),
                events,
                droppedEventCount = 0L,
            ),
        )

        assertEquals(0.015f, metrics.stopOvershoot!!, EPSILON)
        assertEquals(0.10f, metrics.reacquisitionJump!!, EPSILON)
    }

    private fun measurementSample(
        timestampMs: Long,
        raw: FloatArray,
        filtered: FloatArray,
    ): TrackingMeasurementSample = TrackingMeasurementSample(
        captureTimestampMs = timestampMs,
        sensorTimestampNs = timestampMs * 1_000_000L,
        deliveryTimestampMs = timestampMs + 100L,
        latencyMs = 100L,
        mlFps = 30f,
        confidence = Float.NaN,
        facePresent = true,
        predictedOnly = false,
        rawLandmarks = raw,
        filteredLandmarks = filtered,
        velocities = FloatArray(filtered.size),
        rawGeometry = TrackingGeometryExtractor.extract(raw),
        filteredGeometry = TrackingGeometryExtractor.extract(filtered),
    )

    private fun poseOnlyMeasurement(
        timestampMs: Long,
        rawX: Float,
        filteredX: Float,
    ): TrackingMeasurementSample = TrackingMeasurementSample(
        captureTimestampMs = timestampMs,
        sensorTimestampNs = timestampMs * 1_000_000L,
        deliveryTimestampMs = timestampMs + 100L,
        latencyMs = 100L,
        mlFps = 30f,
        confidence = Float.NaN,
        facePresent = true,
        predictedOnly = false,
        rawLandmarks = FloatArray(0),
        filteredLandmarks = FloatArray(0),
        velocities = FloatArray(0),
        rawGeometry = TrackingGeometry(TrackingPose(rawX, 0f, 1f, 0f), FloatArray(0)),
        filteredGeometry = TrackingGeometry(
            TrackingPose(filteredX, 0f, 1f, 0f),
            FloatArray(0),
        ),
    )

    private fun renderSample(timestampMs: Long, translationX: Float): TrackingRenderSample {
        val outer = FloatArray(LipLandmarkTopology.outerContour.size * 2)
        val inner = FloatArray(LipLandmarkTopology.innerContour.size * 2)
        (outer.indices step 2).forEach { index ->
            outer[index] = 0.45f + translationX
            outer[index + 1] = 0.50f
            inner[index] = 0.46f + translationX
            inner[index + 1] = 0.50f
        }
        return TrackingRenderSample(
            renderTimestampMs = timestampMs,
            measurementTimestampMs = timestampMs - 100L,
            sensorTimestampNs = (timestampMs - 100L) * 1_000_000L,
            predictionSeconds = 0.04f,
            viewportWidth = 1080,
            viewportHeight = 2400,
            lipVisible = true,
            outerLipPoints = outer,
            innerLipPoints = inner,
        )
    }

    private fun faceCoordinates(translationX: Float): FloatArray {
        val coordinates = FloatArray(LANDMARK_COUNT * LandmarkRenderFrame.COORDINATE_COUNT)
        setPoint(coordinates, 33, 0.30f + translationX, 0.40f)
        setPoint(coordinates, 133, 0.40f + translationX, 0.40f)
        setPoint(coordinates, 362, 0.60f + translationX, 0.40f)
        setPoint(coordinates, 263, 0.70f + translationX, 0.40f)
        setPoint(coordinates, 168, 0.50f + translationX, 0.45f)
        setPoint(coordinates, 1, 0.50f + translationX, 0.50f)
        setPoint(coordinates, 234, 0.25f + translationX, 0.50f)
        setPoint(coordinates, 454, 0.75f + translationX, 0.50f)
        val lipIndices = LipLandmarkTopology.outerContour + LipLandmarkTopology.innerContour
        lipIndices.forEachIndexed { index, landmarkIndex ->
            val angle = index * 2.0 * Math.PI / lipIndices.size
            setPoint(
                coordinates,
                landmarkIndex,
                0.50f + translationX + 0.08f * cos(angle).toFloat(),
                0.65f + 0.03f * sin(angle).toFloat(),
            )
        }
        return coordinates
    }

    private fun transform(
        coordinates: FloatArray,
        scale: Float,
        rotation: Float,
        tx: Float,
        ty: Float,
    ): FloatArray {
        val cosRotation = cos(rotation)
        val sinRotation = sin(rotation)
        return coordinates.copyOf().also { transformed ->
            var index = 0
            while (index < transformed.size) {
                val x = coordinates[index]
                val y = coordinates[index + 1]
                transformed[index] = scale * (cosRotation * x - sinRotation * y) + tx
                transformed[index + 1] = scale * (sinRotation * x + cosRotation * y) + ty
                transformed[index + 2] = coordinates[index + 2] * scale
                index += LandmarkRenderFrame.COORDINATE_COUNT
            }
        }
    }

    private fun setPoint(coordinates: FloatArray, index: Int, x: Float, y: Float) {
        coordinates[index * LandmarkRenderFrame.COORDINATE_COUNT] = x
        coordinates[index * LandmarkRenderFrame.COORDINATE_COUNT + 1] = y
    }

    companion object {
        private const val LANDMARK_COUNT = 478
        private const val EPSILON = 0.0001f
    }
}
