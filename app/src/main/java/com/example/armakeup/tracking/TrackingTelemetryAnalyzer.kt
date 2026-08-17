package com.example.armakeup.tracking

import com.example.armakeup.makeup.LipLandmarkTopology
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class TrackingJitterMetric(
    val rms: Float,
    val peak: Float,
    val sampleCount: Int,
)

data class TrackingInputQualityMetrics(
    val medianCaptureIntervalMs: Float,
    val p95CaptureIntervalMs: Float,
    val medianMeanLuma: Float,
    val medianLumaStandardDeviation: Float,
    val medianMeanGradient: Float,
    val medianExposureMs: Float,
    val p95ExposureMs: Float,
    val medianSensitivityIso: Float,
    val medianFrameDurationMs: Float,
    val medianRollingShutterSkewMs: Float,
    val medianPoseFitQuality: Float,
    val medianPoseFitResidual: Float,
    val medianPoseFitInlierFraction: Float,
    val maximumThermalStatus: Int,
    val medianBatteryTemperatureCelsius: Float,
)

data class TrackingRenderPerformanceMetrics(
    val lipstickFinishes: List<String>,
    val medianFrameSubmissionCpuMs: Float,
    val p95FrameSubmissionCpuMs: Float,
    val filamentRenderedFrameFraction: Float,
    val medianMaterialCameraCoherence: Float,
    val p05MaterialCameraCoherence: Float,
    val medianMaterialMotionSpeed: Float,
    val p95MaterialTemporalMismatchMs: Float,
    val byFinish: List<TrackingFinishRenderPerformanceMetrics>,
)

data class TrackingFinishRenderPerformanceMetrics(
    val finish: String,
    val renderCount: Int,
    val medianFrameSubmissionCpuMs: Float,
    val p95FrameSubmissionCpuMs: Float,
    val filamentRenderedFrameFraction: Float,
)

data class TrackingTelemetryMetrics(
    val scenario: String,
    val durationMs: Long,
    val measurementCount: Int,
    val renderCount: Int,
    val droppedEventCount: Long,
    val stationaryWindowStartOffsetMs: Long,
    val stationaryWindowDurationMs: Long,
    val medianMlFps: Float,
    val medianLatencyMs: Float,
    val p95LatencyMs: Float,
    val inputQuality: TrackingInputQualityMetrics,
    val renderPerformance: TrackingRenderPerformanceMetrics,
    val rawLipJitter: TrackingJitterMetric,
    val filteredLipJitter: TrackingJitterMetric,
    val rawLocalLipJitter: TrackingJitterMetric,
    val filteredLocalLipJitter: TrackingJitterMetric,
    val displayedLipJitter: TrackingJitterMetric,
    val displayedLipJitterPixels: TrackingJitterMetric,
    val estimatedMotionLagMs: Float?,
    val stopOvershoot: Float?,
    val reacquisitionJump: Float?,
) {
    fun toLogLine(): String = buildString {
        append("scenario=").append(scenario)
        append(" durationMs=").append(durationMs)
        append(" measurements=").append(measurementCount)
        append(" renders=").append(renderCount)
        append(" dropped=").append(droppedEventCount)
        append(" stationaryWindowMs=").append(stationaryWindowStartOffsetMs)
            .append("+").append(stationaryWindowDurationMs)
        append(" mlFpsMedian=").append(medianMlFps.formatMetric())
        append(" latencyMedianMs=").append(medianLatencyMs.formatMetric())
        append(" latencyP95Ms=").append(p95LatencyMs.formatMetric())
        append(" captureIntervalMedianMs=")
            .append(inputQuality.medianCaptureIntervalMs.formatMetric())
        append(" captureIntervalP95Ms=")
            .append(inputQuality.p95CaptureIntervalMs.formatMetric())
        append(" lumaMedian=").append(inputQuality.medianMeanLuma.formatMetric())
        append(" lumaStdMedian=")
            .append(inputQuality.medianLumaStandardDeviation.formatMetric())
        append(" gradientMedian=").append(inputQuality.medianMeanGradient.formatMetric())
        append(" exposureMedianMs=").append(inputQuality.medianExposureMs.formatMetric())
        append(" exposureP95Ms=").append(inputQuality.p95ExposureMs.formatMetric())
        append(" isoMedian=").append(inputQuality.medianSensitivityIso.formatMetric())
        append(" frameDurationMedianMs=")
            .append(inputQuality.medianFrameDurationMs.formatMetric())
        append(" rollingShutterMedianMs=")
            .append(inputQuality.medianRollingShutterSkewMs.formatMetric())
        append(" poseQualityMedian=")
            .append(inputQuality.medianPoseFitQuality.formatMetric())
        append(" poseResidualMedian=")
            .append(inputQuality.medianPoseFitResidual.formatMetric())
        append(" poseInliersMedian=")
            .append(inputQuality.medianPoseFitInlierFraction.formatMetric())
        append(" thermalMax=").append(inputQuality.maximumThermalStatus)
        append(" batteryTempMedianC=")
            .append(inputQuality.medianBatteryTemperatureCelsius.formatMetric())
        append(" finishes=").append(
            renderPerformance.lipstickFinishes.ifEmpty { listOf("UNKNOWN") }.joinToString("|")
        )
        append(" frameCpuMedianMs=")
            .append(renderPerformance.medianFrameSubmissionCpuMs.formatMetric())
        append(" frameCpuP95Ms=")
            .append(renderPerformance.p95FrameSubmissionCpuMs.formatMetric())
        append(" filamentRenderedFraction=")
            .append(renderPerformance.filamentRenderedFrameFraction.formatMetric())
        append(" materialCoherenceMedian=")
            .append(renderPerformance.medianMaterialCameraCoherence.formatMetric())
        append(" materialCoherenceP05=")
            .append(renderPerformance.p05MaterialCameraCoherence.formatMetric())
        append(" materialMotionMedian=")
            .append(renderPerformance.medianMaterialMotionSpeed.formatMetric())
        append(" materialMismatchP95Ms=")
            .append(renderPerformance.p95MaterialTemporalMismatchMs.formatMetric())
        append(" finishFrameCpu=").append(
            renderPerformance.byFinish.joinToString("|") { finish ->
                "${finish.finish}:${finish.renderCount}:" +
                    "${finish.medianFrameSubmissionCpuMs.formatMetric()}:" +
                    "${finish.p95FrameSubmissionCpuMs.formatMetric()}:" +
                    finish.filamentRenderedFrameFraction.formatMetric()
            }.ifEmpty { "n/a" }
        )
        append(" rawJitterRms=").append(rawLipJitter.rms.formatMetric())
        append(" filteredJitterRms=").append(filteredLipJitter.rms.formatMetric())
        append(" rawLocalJitterRms=").append(rawLocalLipJitter.rms.formatMetric())
        append(" filteredLocalJitterRms=").append(filteredLocalLipJitter.rms.formatMetric())
        append(" displayedJitterRms=").append(displayedLipJitter.rms.formatMetric())
        append(" displayedJitterPeak=").append(displayedLipJitter.peak.formatMetric())
        append(" displayedJitterPxRms=").append(displayedLipJitterPixels.rms.formatMetric())
        append(" displayedJitterPxPeak=").append(displayedLipJitterPixels.peak.formatMetric())
        append(" lagMs=").append(estimatedMotionLagMs?.formatMetric() ?: "n/a")
        append(" stopOvershoot=").append(stopOvershoot?.formatMetric() ?: "n/a")
        append(" reacquisitionJump=").append(reacquisitionJump?.formatMetric() ?: "n/a")
    }

    private fun Float.formatMetric(): String = String.format(java.util.Locale.US, "%.6f", this)
}

/** Computes the first V6 numerical baseline from an exact recorded event stream. */
object TrackingTelemetryAnalyzer {
    private val lipIndices = LipLandmarkTopology.outerContour + LipLandmarkTopology.innerContour

    fun analyze(session: TrackingTelemetrySession): TrackingTelemetryMetrics {
        val measurements = session.measurements
        val renders = session.renders.filter { it.lipVisible }
        val eventTimes = session.events.mapNotNull { event ->
            when (event) {
                is TrackingMeasurementSample -> event.deliveryTimestampMs
                is TrackingRenderSample -> event.renderTimestampMs
            }.takeIf { it >= 0L }
        }
        val latencyValues = measurements.map { it.latencyMs.toFloat() }
        val mlFpsValues = measurements.map { it.mlFps }.filter { it.isFinite() && it > 0f }
        val validPoseSamples = measurements.filter {
            it.facePresent && it.rawGeometry.pose.isValid && it.filteredGeometry.pose.isValid
        }
        val stationaryMeasurements = selectStationaryWindow(validPoseSamples)
        val stationaryStartMs = stationaryMeasurements.firstOrNull()?.captureTimestampMs
            ?: eventTimes.minOrNull()
            ?: 0L
        val stationaryEndMs = stationaryMeasurements.lastOrNull()?.captureTimestampMs
            ?: stationaryStartMs
        val stationaryRenders = renders.filter {
            it.measurementTimestampMs in stationaryStartMs..stationaryEndMs
        }
        val stationaryOnly = session.header.scenario.contains("stationary", ignoreCase = true) &&
            !session.header.scenario.contains("mixed", ignoreCase = true)
        val firstEventTimeMs = eventTimes.minOrNull() ?: stationaryStartMs

        return TrackingTelemetryMetrics(
            scenario = session.header.scenario,
            durationMs = if (eventTimes.isEmpty()) 0L else {
                (eventTimes.maxOrNull()!! - eventTimes.minOrNull()!!).coerceAtLeast(0L)
            },
            measurementCount = measurements.size,
            renderCount = renders.size,
            droppedEventCount = session.droppedEventCount,
            stationaryWindowStartOffsetMs = (stationaryStartMs - firstEventTimeMs).coerceAtLeast(0L),
            stationaryWindowDurationMs = (stationaryEndMs - stationaryStartMs).coerceAtLeast(0L),
            medianMlFps = percentile(mlFpsValues, 0.5f),
            medianLatencyMs = percentile(latencyValues, 0.5f),
            p95LatencyMs = percentile(latencyValues, 0.95f),
            inputQuality = analyzeInputQuality(measurements),
            renderPerformance = analyzeRenderPerformance(renders),
            rawLipJitter = pointCloudJitter(
                stationaryMeasurements.mapNotNull {
                    extractLipPoints(it.rawLandmarks)
                },
            ),
            filteredLipJitter = pointCloudJitter(
                stationaryMeasurements.mapNotNull { extractLipPoints(it.filteredLandmarks) },
            ),
            rawLocalLipJitter = pointCloudJitter(
                stationaryMeasurements.map { it.rawGeometry.localLipCoordinates },
            ),
            filteredLocalLipJitter = pointCloudJitter(
                stationaryMeasurements.map { it.filteredGeometry.localLipCoordinates },
            ),
            displayedLipJitter = pointCloudJitter(
                stationaryRenders.map { combine(it.outerLipPoints, it.innerLipPoints) },
            ),
            displayedLipJitterPixels = pointCloudJitter(
                stationaryRenders.map(::renderPointsInPixels),
            ),
            estimatedMotionLagMs = if (stationaryOnly) null else {
                estimateMotionLag(validPoseSamples)
            },
            stopOvershoot = if (stationaryOnly) null else {
                estimateStopOvershoot(validPoseSamples)
            },
            reacquisitionJump = estimateReacquisitionJump(measurements),
        )
    }

    private fun analyzeRenderPerformance(
        renders: List<TrackingRenderSample>,
    ): TrackingRenderPerformanceMetrics {
        val cpuTimes = renders.map { it.frameSubmissionCpuMs }
            .filter { it.isFinite() && it >= 0f }
        val coherence = renders.map { it.materialCameraCoherence }
            .filter { it.isFinite() }
        val motionSpeeds = renders.map { it.materialMotionSpeed }
            .filter { it.isFinite() && it >= 0f }
        val temporalMismatch = renders.map { it.materialTemporalMismatchMs }
            .filter { it.isFinite() && it >= 0f }
        val renderedFraction = if (renders.isEmpty()) {
            Float.NaN
        } else {
            renders.count { it.filamentFrameRendered }.toFloat() / renders.size
        }
        return TrackingRenderPerformanceMetrics(
            lipstickFinishes = renders.map { it.lipstickFinish }
                .filter { it.isNotBlank() && it != "UNKNOWN" }
                .distinct()
                .sorted(),
            medianFrameSubmissionCpuMs = percentile(cpuTimes, 0.5f),
            p95FrameSubmissionCpuMs = percentile(cpuTimes, 0.95f),
            filamentRenderedFrameFraction = renderedFraction,
            medianMaterialCameraCoherence = percentile(coherence, 0.5f),
            p05MaterialCameraCoherence = percentile(coherence, 0.05f),
            medianMaterialMotionSpeed = percentile(motionSpeeds, 0.5f),
            p95MaterialTemporalMismatchMs = percentile(temporalMismatch, 0.95f),
            byFinish = renders
                .filter { it.lipstickFinish.isNotBlank() && it.lipstickFinish != "UNKNOWN" }
                .groupBy { it.lipstickFinish }
                .toSortedMap()
                .map { (finish, finishRenders) ->
                    val finishCpuTimes = finishRenders.map { it.frameSubmissionCpuMs }
                        .filter { it.isFinite() && it >= 0f }
                    TrackingFinishRenderPerformanceMetrics(
                        finish = finish,
                        renderCount = finishRenders.size,
                        medianFrameSubmissionCpuMs = percentile(finishCpuTimes, 0.5f),
                        p95FrameSubmissionCpuMs = percentile(finishCpuTimes, 0.95f),
                        filamentRenderedFrameFraction = finishRenders
                            .count { it.filamentFrameRendered }.toFloat() / finishRenders.size,
                    )
                },
        )
    }

    private fun analyzeInputQuality(
        measurements: List<TrackingMeasurementSample>,
    ): TrackingInputQualityMetrics {
        val captureIntervals = measurements.map { it.captureIntervalMs.toFloat() }
            .filter { it > 0f }
        val luma = measurements.map { it.frameQuality.meanLuma }.finiteValues()
        val lumaStandardDeviation = measurements
            .map { it.frameQuality.lumaStandardDeviation }.finiteValues()
        val gradients = measurements.map { it.frameQuality.meanGradient }.finiteValues()
        val exposureMs = measurements.map { it.frameQuality.exposureTimeNs }
            .filter { it >= 0L }
            .map { it / NANOSECONDS_PER_MILLISECOND }
        val sensitivityIso = measurements.map { it.frameQuality.sensitivityIso.toFloat() }
            .filter { it >= 0f }
        val frameDurationMs = measurements.map { it.frameQuality.frameDurationNs }
            .filter { it >= 0L }
            .map { it / NANOSECONDS_PER_MILLISECOND }
        val rollingShutterMs = measurements.map { it.frameQuality.rollingShutterSkewNs }
            .filter { it >= 0L }
            .map { it / NANOSECONDS_PER_MILLISECOND }
        val poseQualities = measurements.map { it.poseFitQuality.quality }.finiteValues()
        val poseResiduals = measurements.map {
            it.poseFitQuality.normalizedRmsResidual
        }.finiteValues()
        val poseInlierFractions = measurements.map {
            it.poseFitQuality.inlierFraction
        }.finiteValues()
        val thermalStatuses = measurements.map { it.deviceState.thermalStatus }
            .filter { it >= 0 }
        val batteryTemperatures = measurements.map {
            it.deviceState.batteryTemperatureCelsius
        }.finiteValues()
        return TrackingInputQualityMetrics(
            medianCaptureIntervalMs = percentile(captureIntervals, 0.5f),
            p95CaptureIntervalMs = percentile(captureIntervals, 0.95f),
            medianMeanLuma = percentile(luma, 0.5f),
            medianLumaStandardDeviation = percentile(lumaStandardDeviation, 0.5f),
            medianMeanGradient = percentile(gradients, 0.5f),
            medianExposureMs = percentile(exposureMs, 0.5f),
            p95ExposureMs = percentile(exposureMs, 0.95f),
            medianSensitivityIso = percentile(sensitivityIso, 0.5f),
            medianFrameDurationMs = percentile(frameDurationMs, 0.5f),
            medianRollingShutterSkewMs = percentile(rollingShutterMs, 0.5f),
            medianPoseFitQuality = percentile(poseQualities, 0.5f),
            medianPoseFitResidual = percentile(poseResiduals, 0.5f),
            medianPoseFitInlierFraction = percentile(poseInlierFractions, 0.5f),
            maximumThermalStatus = thermalStatuses.maxOrNull() ?: -1,
            medianBatteryTemperatureCelsius = percentile(batteryTemperatures, 0.5f),
        )
    }

    private fun List<Float>.finiteValues(): List<Float> = filter { it.isFinite() }

    private fun selectStationaryWindow(
        samples: List<TrackingMeasurementSample>,
    ): List<TrackingMeasurementSample> {
        if (samples.size < 2) return samples
        val totalDurationMs = samples.last().captureTimestampMs - samples.first().captureTimestampMs
        if (totalDurationMs <= STATIONARY_WINDOW_MS) return samples

        var bestStart = 0
        var bestEndExclusive = 0
        var bestScore = Float.POSITIVE_INFINITY
        var endExclusive = 0
        samples.indices.forEach { start ->
            if (endExclusive < start + 1) endExclusive = start + 1
            val targetEndMs = samples[start].captureTimestampMs + STATIONARY_WINDOW_MS
            while (
                endExclusive < samples.size &&
                samples[endExclusive].captureTimestampMs <= targetEndMs
            ) {
                endExclusive++
            }
            val durationMs = samples[endExclusive - 1].captureTimestampMs -
                samples[start].captureTimestampMs
            if (durationMs < MINIMUM_STATIONARY_WINDOW_MS) return@forEach
            val score = stationaryMotionScore(samples, start, endExclusive)
            if (score < bestScore) {
                bestScore = score
                bestStart = start
                bestEndExclusive = endExclusive
            }
        }
        return if (bestEndExclusive > bestStart) {
            samples.subList(bestStart, bestEndExclusive)
        } else {
            samples
        }
    }

    private fun stationaryMotionScore(
        samples: List<TrackingMeasurementSample>,
        start: Int,
        endExclusive: Int,
    ): Float {
        var score = 0f
        var transitionCount = 0
        for (index in start + 1 until endExclusive) {
            val previous = samples[index - 1].rawGeometry
            val current = samples[index].rawGeometry
            val meanScale = ((previous.pose.scale + current.pose.scale) * 0.5f)
                .coerceAtLeast(MINIMUM_POSE_SCALE)
            val translation = hypot(
                current.pose.centerX - previous.pose.centerX,
                current.pose.centerY - previous.pose.centerY,
            ) / meanScale
            val scaleChange = kotlin.math.abs(ln(current.pose.scale / previous.pose.scale))
            val rotationChange = kotlin.math.abs(
                wrapAngle(current.pose.rotationRadians - previous.pose.rotationRadians),
            )
            val localLipChange = pointCloudFrameDelta(
                previous.localLipCoordinates,
                current.localLipCoordinates,
            )
            score += translation + scaleChange * SCALE_MOTION_WEIGHT +
                rotationChange * ROTATION_MOTION_WEIGHT +
                localLipChange * LOCAL_DEFORMATION_WEIGHT
            transitionCount++
        }
        return if (transitionCount == 0) Float.POSITIVE_INFINITY else score / transitionCount
    }

    private fun pointCloudFrameDelta(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size || first.isEmpty() || first.size % 2 != 0) return 0f
        var squaredDistance = 0.0
        var count = 0
        var index = 0
        while (index < first.size) {
            val deltaX = second[index] - first[index]
            val deltaY = second[index + 1] - first[index + 1]
            squaredDistance += deltaX * deltaX + deltaY * deltaY
            count++
            index += 2
        }
        return sqrt(squaredDistance / count).toFloat()
    }

    private fun wrapAngle(angle: Float): Float {
        var wrapped = angle
        while (wrapped > Math.PI) wrapped -= (2.0 * Math.PI).toFloat()
        while (wrapped < -Math.PI) wrapped += (2.0 * Math.PI).toFloat()
        return wrapped
    }

    private fun pointCloudJitter(frames: List<FloatArray>): TrackingJitterMetric {
        val coordinateCount = frames.firstOrNull()?.size ?: return EMPTY_JITTER
        val usable = frames.filter { it.size == coordinateCount && coordinateCount % 2 == 0 }
        if (usable.size < 2) return TrackingJitterMetric(0f, 0f, usable.size)

        val means = FloatArray(coordinateCount)
        usable.forEach { frame ->
            frame.indices.forEach { index -> means[index] += frame[index] }
        }
        means.indices.forEach { index -> means[index] /= usable.size }

        var squaredDistanceSum = 0.0
        var peak = 0f
        var distanceCount = 0
        usable.forEach { frame ->
            var index = 0
            while (index < coordinateCount) {
                val distance = hypot(frame[index] - means[index], frame[index + 1] - means[index + 1])
                squaredDistanceSum += distance * distance
                peak = max(peak, distance)
                distanceCount++
                index += 2
            }
        }
        return TrackingJitterMetric(
            rms = sqrt(squaredDistanceSum / distanceCount).toFloat(),
            peak = peak,
            sampleCount = usable.size,
        )
    }

    private fun estimateMotionLag(samples: List<TrackingMeasurementSample>): Float? {
        if (samples.size < MINIMUM_MOTION_SAMPLE_COUNT) return null
        val rawRange = positionRange(samples.map { it.rawGeometry.pose })
        if (rawRange < MINIMUM_MEASURABLE_MOTION) return null

        val maximumLag = minOf(MAXIMUM_LAG_SAMPLES, samples.size / 3)
        var bestLag = 0
        var bestError = Double.POSITIVE_INFINITY
        for (lag in 0..maximumLag) {
            val pairCount = samples.size - lag
            var offsetX = 0.0
            var offsetY = 0.0
            repeat(pairCount) { index ->
                val raw = samples[index].rawGeometry.pose
                val filtered = samples[index + lag].filteredGeometry.pose
                offsetX += filtered.centerX - raw.centerX
                offsetY += filtered.centerY - raw.centerY
            }
            offsetX /= pairCount
            offsetY /= pairCount
            var squaredError = 0.0
            repeat(pairCount) { index ->
                val raw = samples[index].rawGeometry.pose
                val filtered = samples[index + lag].filteredGeometry.pose
                val errorX = filtered.centerX - raw.centerX - offsetX
                val errorY = filtered.centerY - raw.centerY - offsetY
                squaredError += errorX * errorX + errorY * errorY
            }
            val error = squaredError / pairCount
            if (error < bestError) {
                bestError = error
                bestLag = lag
            }
        }
        val intervals = samples.zipWithNext { first, second ->
            (second.captureTimestampMs - first.captureTimestampMs).toFloat()
        }.filter { it > 0f }
        if (intervals.isEmpty()) return null
        return bestLag * percentile(intervals, 0.5f)
    }

    private fun estimateStopOvershoot(samples: List<TrackingMeasurementSample>): Float? {
        if (samples.size < MINIMUM_STOP_SAMPLE_COUNT) return null
        val speeds = samples.zipWithNext { first, second ->
            val elapsedSeconds = (second.captureTimestampMs - first.captureTimestampMs) / 1_000f
            if (elapsedSeconds <= 0f) {
                0f
            } else {
                hypot(
                    second.rawGeometry.pose.centerX - first.rawGeometry.pose.centerX,
                    second.rawGeometry.pose.centerY - first.rawGeometry.pose.centerY,
                ) / elapsedSeconds
            }
        }
        val maximumSpeed = speeds.maxOrNull() ?: return null
        if (maximumSpeed < MINIMUM_STOP_MOTION_SPEED) return null
        val movingThreshold = max(MINIMUM_STOP_MOTION_SPEED, maximumSpeed * STOP_SPEED_FRACTION)
        var stopIndex = -1
        for (index in 1 until speeds.size - STOP_CONFIRMATION_INTERVALS) {
            if (
                speeds[index - 1] >= movingThreshold &&
                (0 until STOP_CONFIRMATION_INTERVALS).all {
                    speeds[index + it] < movingThreshold
                }
            ) {
                stopIndex = index
                break
            }
        }
        if (stopIndex < 1) return null

        val directionStartIndex = (stopIndex - DIRECTION_LOOKBACK_SAMPLES).coerceAtLeast(0)
        val directionStart = samples[directionStartIndex].rawGeometry.pose
        val stopPose = samples[stopIndex].rawGeometry.pose
        val directionX = stopPose.centerX - directionStart.centerX
        val directionY = stopPose.centerY - directionStart.centerY
        val directionLength = hypot(directionX, directionY)
        if (directionLength < MINIMUM_MEASURABLE_MOTION) return null
        val unitX = directionX / directionLength
        val unitY = directionY / directionLength

        var stopSegmentEndIndex = samples.lastIndex
        for (speedIndex in stopIndex + STOP_CONFIRMATION_INTERVALS until speeds.size) {
            if (speeds[speedIndex] >= movingThreshold) {
                // speeds[i] describes samples[i] -> samples[i + 1]; the first point belongs to
                // the completed stop, while samples[i + 1] starts a different motion segment.
                stopSegmentEndIndex = speedIndex
                break
            }
        }
        val targetStartIndex = (
            stopSegmentEndIndex - STOP_TARGET_SAMPLE_COUNT + 1
        ).coerceAtLeast(stopIndex)
        val targetSamples = samples.subList(targetStartIndex, stopSegmentEndIndex + 1)
        val targetX = targetSamples.map { it.rawGeometry.pose.centerX }.average().toFloat()
        val targetY = targetSamples.map { it.rawGeometry.pose.centerY }.average().toFloat()
        var overshoot = 0f
        for (index in stopIndex..stopSegmentEndIndex) {
            val filtered = samples[index].filteredGeometry.pose
            val projection = (filtered.centerX - targetX) * unitX +
                (filtered.centerY - targetY) * unitY
            overshoot = max(overshoot, projection)
        }
        return overshoot
    }

    private fun estimateReacquisitionJump(samples: List<TrackingMeasurementSample>): Float? {
        var lastVisiblePose: TrackingPose? = null
        var gapSeen = false
        var maximumJump: Float? = null
        samples.forEach { sample ->
            if (!sample.facePresent) {
                gapSeen = true
                return@forEach
            }
            val pose = sample.filteredGeometry.pose.takeIf { it.isValid } ?: return@forEach
            val beforeGap = lastVisiblePose
            if (gapSeen && beforeGap != null) {
                val jump = hypot(pose.centerX - beforeGap.centerX, pose.centerY - beforeGap.centerY)
                maximumJump = max(maximumJump ?: 0f, jump)
            }
            lastVisiblePose = pose
            gapSeen = false
        }
        return maximumJump
    }

    private fun extractLipPoints(landmarks: FloatArray): FloatArray? {
        val requiredLandmarkCount = (lipIndices.maxOrNull() ?: return null) + 1
        if (landmarks.size < requiredLandmarkCount * LandmarkRenderFrame.COORDINATE_COUNT) {
            return null
        }
        return FloatArray(lipIndices.size * 2).also { points ->
            lipIndices.forEachIndexed { pointIndex, landmarkIndex ->
                val coordinateIndex = landmarkIndex * LandmarkRenderFrame.COORDINATE_COUNT
                points[pointIndex * 2] = landmarks[coordinateIndex]
                points[pointIndex * 2 + 1] = landmarks[coordinateIndex + 1]
            }
        }
    }

    private fun combine(first: FloatArray, second: FloatArray): FloatArray =
        FloatArray(first.size + second.size).also { combined ->
            first.copyInto(combined)
            second.copyInto(combined, destinationOffset = first.size)
        }

    private fun renderPointsInPixels(sample: TrackingRenderSample): FloatArray {
        val normalized = combine(sample.outerLipPoints, sample.innerLipPoints)
        var index = 0
        while (index < normalized.size) {
            normalized[index] *= sample.viewportWidth
            normalized[index + 1] *= sample.viewportHeight
            index += 2
        }
        return normalized
    }

    private fun positionRange(poses: List<TrackingPose>): Float {
        if (poses.isEmpty()) return 0f
        val minX = poses.minOf { it.centerX }
        val maxX = poses.maxOf { it.centerX }
        val minY = poses.minOf { it.centerY }
        val maxY = poses.maxOf { it.centerY }
        return hypot(maxX - minX, maxY - minY)
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        val index = ((sorted.lastIndex) * fraction).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private val EMPTY_JITTER = TrackingJitterMetric(Float.NaN, Float.NaN, 0)
    private const val MINIMUM_MOTION_SAMPLE_COUNT = 8
    private const val MINIMUM_STOP_SAMPLE_COUNT = 10
    private const val MAXIMUM_LAG_SAMPLES = 6
    private const val MINIMUM_MEASURABLE_MOTION = 0.002f
    private const val MINIMUM_STOP_MOTION_SPEED = 0.01f
    private const val STOP_SPEED_FRACTION = 0.2f
    private const val STOP_CONFIRMATION_INTERVALS = 3
    private const val DIRECTION_LOOKBACK_SAMPLES = 3
    private const val STOP_TARGET_SAMPLE_COUNT = 3
    private const val STATIONARY_WINDOW_MS = 2_000L
    private const val MINIMUM_STATIONARY_WINDOW_MS = 1_900L
    private const val NANOSECONDS_PER_MILLISECOND = 1_000_000f
    private const val MINIMUM_POSE_SCALE = 1e-5f
    private const val SCALE_MOTION_WEIGHT = 0.5f
    private const val ROTATION_MOTION_WEIGHT = 0.2f
    private const val LOCAL_DEFORMATION_WEIGHT = 0.25f
}
