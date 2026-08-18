package com.example.armakeup.tracking

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

data class TrackingTelemetryHeader(
    val scenario: String,
    val startedAtEpochMs: Long,
)

data class TrackingTelemetrySession(
    val header: TrackingTelemetryHeader,
    val events: List<TrackingTelemetryEvent>,
    val droppedEventCount: Long,
) {
    val measurements: List<TrackingMeasurementSample>
        get() = events.filterIsInstance<TrackingMeasurementSample>()

    val renders: List<TrackingRenderSample>
        get() = events.filterIsInstance<TrackingRenderSample>()
}

/** Versioned binary format used by device recording and deterministic JVM replay tests. */
object TrackingTelemetryCodec {
    fun writeHeader(output: OutputStream, header: TrackingTelemetryHeader) {
        val data = output.asDataOutput()
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(header.scenario)
        data.writeLong(header.startedAtEpochMs)
    }

    fun writeEvent(output: OutputStream, event: TrackingTelemetryEvent) {
        val data = output.asDataOutput()
        when (event) {
            is TrackingMeasurementSample -> writeMeasurement(data, event)
            is TrackingRenderSample -> writeRender(data, event)
        }
    }

    fun writeFooter(output: OutputStream, droppedEventCount: Long) {
        val data = output.asDataOutput()
        data.writeByte(EVENT_FOOTER)
        data.writeLong(droppedEventCount)
    }

    fun read(input: InputStream): TrackingTelemetrySession {
        val data = input.asDataInput()
        require(data.readInt() == MAGIC) { "Not an AR Makeup V6 tracking telemetry file" }
        val version = data.readInt()
        require(version in MINIMUM_SUPPORTED_VERSION..VERSION) {
            "Unsupported tracking telemetry version $version"
        }
        val header = TrackingTelemetryHeader(
            scenario = data.readUTF(),
            startedAtEpochMs = data.readLong(),
        )
        val events = ArrayList<TrackingTelemetryEvent>()
        var droppedEventCount = 0L
        while (true) {
            val eventType = try {
                data.readUnsignedByte()
            } catch (_: EOFException) {
                break
            }
            when (eventType) {
                EVENT_MEASUREMENT -> events += readMeasurement(data, version)
                EVENT_RENDER -> events += readRender(data, version)
                EVENT_FOOTER -> {
                    droppedEventCount = data.readLong()
                    break
                }
                else -> error("Unknown tracking telemetry event type $eventType")
            }
        }
        return TrackingTelemetrySession(header, events, droppedEventCount)
    }

    private fun writeMeasurement(data: DataOutputStream, sample: TrackingMeasurementSample) {
        data.writeByte(EVENT_MEASUREMENT)
        data.writeLong(sample.captureTimestampMs)
        data.writeLong(sample.sensorTimestampNs)
        data.writeLong(sample.deliveryTimestampMs)
        data.writeLong(sample.latencyMs)
        data.writeFloat(sample.mlFps)
        data.writeFloat(sample.confidence)
        data.writeBoolean(sample.facePresent)
        data.writeBoolean(sample.predictedOnly)
        data.writeFloatArray(sample.rawLandmarks)
        data.writeFloatArray(sample.filteredLandmarks)
        data.writeFloatArray(sample.velocities)
        data.writeGeometry(sample.rawGeometry)
        data.writeGeometry(sample.filteredGeometry)
        data.writeLong(sample.captureIntervalMs)
        data.writeFrameQuality(sample.frameQuality)
        data.writePoseFitQuality(sample.poseFitQuality)
        data.writeDeviceState(sample.deviceState)
        data.writePipelineTiming(sample.pipelineTiming)
        data.writeFloatArray(sample.facialTransformationMatrix)
    }

    private fun readMeasurement(
        data: DataInputStream,
        version: Int,
    ): TrackingMeasurementSample {
        val base = TrackingMeasurementSample(
            captureTimestampMs = data.readLong(),
            sensorTimestampNs = data.readLong(),
            deliveryTimestampMs = data.readLong(),
            latencyMs = data.readLong(),
            mlFps = data.readFloat(),
            confidence = data.readFloat(),
            facePresent = data.readBoolean(),
            predictedOnly = data.readBoolean(),
            rawLandmarks = data.readFloatArray(),
            filteredLandmarks = data.readFloatArray(),
            velocities = data.readFloatArray(),
            rawGeometry = data.readGeometry(),
            filteredGeometry = data.readGeometry(),
        )
        val withInputQuality = if (version >= VERSION_WITH_INPUT_QUALITY) {
            base.copy(
                captureIntervalMs = data.readLong(),
                frameQuality = data.readFrameQuality(),
                poseFitQuality = data.readPoseFitQuality(),
                deviceState = data.readDeviceState(),
            )
        } else {
            base
        }
        return if (version >= VERSION_WITH_PIPELINE_TIMING_AND_3D_TRANSFORM) {
            withInputQuality.copy(
                pipelineTiming = data.readPipelineTiming(),
                facialTransformationMatrix = data.readFloatArray(),
            )
        } else {
            withInputQuality
        }
    }

    private fun writeRender(data: DataOutputStream, sample: TrackingRenderSample) {
        data.writeByte(EVENT_RENDER)
        data.writeLong(sample.renderTimestampMs)
        data.writeLong(sample.measurementTimestampMs)
        data.writeLong(sample.sensorTimestampNs)
        data.writeFloat(sample.predictionSeconds)
        data.writeInt(sample.viewportWidth)
        data.writeInt(sample.viewportHeight)
        data.writeBoolean(sample.lipVisible)
        data.writeFloatArray(sample.outerLipPoints)
        data.writeFloatArray(sample.innerLipPoints)
        data.writeUTF(sample.lipstickFinish)
        data.writeFloat(sample.materialCameraCoherence)
        data.writeFloat(sample.materialMotionSpeed)
        data.writeFloat(sample.materialTemporalMismatchMs)
        data.writeFloat(sample.frameSubmissionCpuMs)
        data.writeBoolean(sample.filamentFrameRendered)
        data.writeBoolean(sample.gyroscopeApplied)
        data.writeFloat(sample.gyroscopeIntervalMs)
        data.writeFloat(sample.gyroscopeRotationX)
        data.writeFloat(sample.gyroscopeRotationY)
        data.writeFloat(sample.gyroscopeRotationZ)
        data.writeFloat(sample.gyroscopeTranslationX)
        data.writeFloat(sample.gyroscopeTranslationY)
        data.writeFloat(sample.gyroscopeRollRadians)
        data.writeFloat(sample.cameraMotionPredictionSeconds)
        data.writeFloat(sample.globalPredictionCoverage)
        data.writeRenderTiming(sample.renderTiming)
        data.writeBoolean(sample.displayQueueProtectionEnabled)
    }

    private fun readRender(data: DataInputStream, version: Int): TrackingRenderSample {
        val base = TrackingRenderSample(
            renderTimestampMs = data.readLong(),
            measurementTimestampMs = data.readLong(),
            sensorTimestampNs = data.readLong(),
            predictionSeconds = data.readFloat(),
            viewportWidth = data.readInt(),
            viewportHeight = data.readInt(),
            lipVisible = data.readBoolean(),
            outerLipPoints = data.readFloatArray(),
            innerLipPoints = data.readFloatArray(),
        )
        val withMaterial = if (version >= VERSION_WITH_MATERIAL_TEMPORAL_STATE) {
            base.copy(
                lipstickFinish = data.readUTF(),
                materialCameraCoherence = data.readFloat(),
                materialMotionSpeed = data.readFloat(),
                materialTemporalMismatchMs = data.readFloat(),
                frameSubmissionCpuMs = data.readFloat(),
                filamentFrameRendered = data.readBoolean(),
            )
        } else {
            base
        }
        val withGyroscope = if (version >= VERSION_WITH_GYROSCOPE_CORRECTION) {
            withMaterial.copy(
                gyroscopeApplied = data.readBoolean(),
                gyroscopeIntervalMs = data.readFloat(),
                gyroscopeRotationX = data.readFloat(),
                gyroscopeRotationY = data.readFloat(),
                gyroscopeRotationZ = data.readFloat(),
                gyroscopeTranslationX = data.readFloat(),
                gyroscopeTranslationY = data.readFloat(),
                gyroscopeRollRadians = data.readFloat(),
            )
        } else {
            withMaterial
        }
        val withPredictionCoverage = if (version >= VERSION_WITH_PREDICTION_COVERAGE) {
            withGyroscope.copy(
                cameraMotionPredictionSeconds = data.readFloat(),
                globalPredictionCoverage = data.readFloat(),
            )
        } else {
            withGyroscope
        }
        val withRenderTimeline = if (version >= VERSION_WITH_RENDER_TIMELINE) {
            withPredictionCoverage.copy(renderTiming = data.readRenderTiming(version))
        } else {
            withPredictionCoverage
        }
        return if (version >= VERSION_WITH_DISPLAY_QUEUE_PROTECTION) {
            withRenderTimeline.copy(displayQueueProtectionEnabled = data.readBoolean())
        } else {
            withRenderTimeline
        }
    }

    private fun DataOutputStream.writeRenderTiming(timing: TrackingRenderTiming) {
        writeLong(timing.vsyncTimestampNs)
        writeLong(timing.renderStartTimestampNs)
        writeLong(timing.cameraFrameSelectedTimestampNs)
        writeLong(timing.cameraFrameSensorTimestampNs)
        writeLong(timing.geometryUploadAcceptedTimestampNs)
        writeLong(timing.renderSubmitTimestampNs)
        writeLong(timing.presentationTimestampNs)
        writeLong(timing.frameTimelineVsyncId)
        writeLong(timing.expectedPresentationTimestampNs)
        writeLong(timing.renderDeadlineTimestampNs)
    }

    private fun DataInputStream.readRenderTiming(version: Int): TrackingRenderTiming {
        val legacyTimeline = TrackingRenderTiming(
            vsyncTimestampNs = readLong(),
            renderStartTimestampNs = readLong(),
            cameraFrameSelectedTimestampNs = readLong(),
            cameraFrameSensorTimestampNs = readLong(),
            geometryUploadAcceptedTimestampNs = readLong(),
            renderSubmitTimestampNs = readLong(),
            presentationTimestampNs = readLong(),
            frameTimelineVsyncId = TrackingRenderTiming.UNKNOWN_TIMESTAMP_NS,
            expectedPresentationTimestampNs = TrackingRenderTiming.UNKNOWN_TIMESTAMP_NS,
            renderDeadlineTimestampNs = TrackingRenderTiming.UNKNOWN_TIMESTAMP_NS,
        )
        return if (version >= VERSION_WITH_FRAME_TIMELINE_CORRELATION) {
            legacyTimeline.copy(
                frameTimelineVsyncId = readLong(),
                expectedPresentationTimestampNs = readLong(),
                renderDeadlineTimestampNs = readLong(),
            )
        } else {
            legacyTimeline
        }
    }

    private fun DataOutputStream.writeGeometry(geometry: TrackingGeometry) {
        writeFloat(geometry.pose.centerX)
        writeFloat(geometry.pose.centerY)
        writeFloat(geometry.pose.scale)
        writeFloat(geometry.pose.rotationRadians)
        writeFloatArray(geometry.localLipCoordinates)
    }

    private fun DataInputStream.readGeometry(): TrackingGeometry = TrackingGeometry(
        pose = TrackingPose(
            centerX = readFloat(),
            centerY = readFloat(),
            scale = readFloat(),
            rotationRadians = readFloat(),
        ),
        localLipCoordinates = readFloatArray(),
    )

    private fun DataOutputStream.writeFrameQuality(quality: TrackingFrameQuality) {
        writeFloat(quality.meanLuma)
        writeFloat(quality.lumaStandardDeviation)
        writeFloat(quality.meanGradient)
        writeLong(quality.exposureTimeNs)
        writeInt(quality.sensitivityIso)
        writeLong(quality.frameDurationNs)
        writeLong(quality.rollingShutterSkewNs)
        writeInt(quality.aeState)
    }

    private fun DataInputStream.readFrameQuality(): TrackingFrameQuality = TrackingFrameQuality(
        meanLuma = readFloat(),
        lumaStandardDeviation = readFloat(),
        meanGradient = readFloat(),
        exposureTimeNs = readLong(),
        sensitivityIso = readInt(),
        frameDurationNs = readLong(),
        rollingShutterSkewNs = readLong(),
        aeState = readInt(),
    )

    private fun DataOutputStream.writePoseFitQuality(quality: TrackingPoseFitQuality) {
        writeFloat(quality.normalizedRmsResidual)
        writeFloat(quality.inlierFraction)
        writeFloat(quality.quality)
    }

    private fun DataInputStream.readPoseFitQuality(): TrackingPoseFitQuality =
        TrackingPoseFitQuality(
            normalizedRmsResidual = readFloat(),
            inlierFraction = readFloat(),
            quality = readFloat(),
        )

    private fun DataOutputStream.writeDeviceState(state: TrackingDeviceState) {
        writeInt(state.thermalStatus)
        writeFloat(state.batteryTemperatureCelsius)
    }

    private fun DataInputStream.readDeviceState(): TrackingDeviceState = TrackingDeviceState(
        thermalStatus = readInt(),
        batteryTemperatureCelsius = readFloat(),
    )

    private fun DataOutputStream.writePipelineTiming(timing: TrackingPipelineTiming) {
        writeLong(timing.analysisStartTimestampMs)
        writeLong(timing.submitTimestampMs)
        writeLong(timing.callbackTimestampMs)
        writeLong(timing.callbackHandlerStartTimestampMs)
        writeFloat(timing.rgbaCopyDurationMs)
        writeFloat(timing.qualityAnalysisDurationMs)
        writeFloat(timing.resultProcessingDurationMs)
    }

    private fun DataInputStream.readPipelineTiming(): TrackingPipelineTiming =
        TrackingPipelineTiming(
            analysisStartTimestampMs = readLong(),
            submitTimestampMs = readLong(),
            callbackTimestampMs = readLong(),
            callbackHandlerStartTimestampMs = readLong(),
            rgbaCopyDurationMs = readFloat(),
            qualityAnalysisDurationMs = readFloat(),
            resultProcessingDurationMs = readFloat(),
        )

    private fun DataOutputStream.writeFloatArray(values: FloatArray) {
        writeInt(values.size)
        values.forEach(::writeFloat)
    }

    private fun DataInputStream.readFloatArray(): FloatArray {
        val size = readInt()
        require(size in 0..MAXIMUM_FLOAT_ARRAY_SIZE) {
            "Invalid tracking telemetry float array size $size"
        }
        return FloatArray(size) { readFloat() }
    }

    private fun OutputStream.asDataOutput(): DataOutputStream =
        this as? DataOutputStream ?: DataOutputStream(this)

    private fun InputStream.asDataInput(): DataInputStream =
        this as? DataInputStream ?: DataInputStream(this)

    private const val MAGIC = 0x41525636 // "ARV6"
    private const val VERSION = 9
    private const val MINIMUM_SUPPORTED_VERSION = 1
    private const val VERSION_WITH_INPUT_QUALITY = 2
    private const val VERSION_WITH_MATERIAL_TEMPORAL_STATE = 3
    private const val VERSION_WITH_GYROSCOPE_CORRECTION = 4
    private const val VERSION_WITH_PREDICTION_COVERAGE = 5
    private const val VERSION_WITH_PIPELINE_TIMING_AND_3D_TRANSFORM = 6
    private const val VERSION_WITH_RENDER_TIMELINE = 7
    private const val VERSION_WITH_FRAME_TIMELINE_CORRELATION = 8
    private const val VERSION_WITH_DISPLAY_QUEUE_PROTECTION = 9
    private const val EVENT_MEASUREMENT = 1
    private const val EVENT_RENDER = 2
    private const val EVENT_FOOTER = 0x7f
    private const val MAXIMUM_FLOAT_ARRAY_SIZE = 10_000
}
