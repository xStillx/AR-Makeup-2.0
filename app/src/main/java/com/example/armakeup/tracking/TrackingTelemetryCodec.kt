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
                EVENT_RENDER -> events += readRender(data)
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
        return if (version >= VERSION_WITH_INPUT_QUALITY) {
            base.copy(
                captureIntervalMs = data.readLong(),
                frameQuality = data.readFrameQuality(),
                poseFitQuality = data.readPoseFitQuality(),
                deviceState = data.readDeviceState(),
            )
        } else {
            base
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
    }

    private fun readRender(data: DataInputStream): TrackingRenderSample = TrackingRenderSample(
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
    private const val VERSION = 2
    private const val MINIMUM_SUPPORTED_VERSION = 1
    private const val VERSION_WITH_INPUT_QUALITY = 2
    private const val EVENT_MEASUREMENT = 1
    private const val EVENT_RENDER = 2
    private const val EVENT_FOOTER = 0x7f
    private const val MAXIMUM_FLOAT_ARRAY_SIZE = 10_000
}
