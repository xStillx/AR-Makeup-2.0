package com.example.armakeup.tracking

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Non-blocking debug recorder. It stores landmarks and geometry only; camera pixels never enter the
 * recording. Events are copied before they reach this sink, queued with a fixed capacity and
 * serialized by one dedicated writer thread.
 */
class TrackingTelemetryRecorder private constructor(
    val outputFile: File,
    scenario: String,
    val requestedDurationMs: Long,
    val requestedWarmupMs: Long,
) : TrackingTelemetrySink, AutoCloseable {
    private val acceptingEvents = AtomicBoolean(true)
    private val droppedEventCount = AtomicLong(0L)
    private val eventQueue = ArrayBlockingQueue<TrackingTelemetryEvent>(EVENT_QUEUE_CAPACITY)
    private val header = TrackingTelemetryHeader(
        scenario = scenario,
        startedAtEpochMs = System.currentTimeMillis() + requestedWarmupMs,
    )
    private val recordingStartsAtUptimeMs = SystemClock.uptimeMillis() + requestedWarmupMs
    private val writerThread = Thread(::writeEvents, WRITER_THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    override fun recordMeasurement(sample: TrackingMeasurementSample) {
        offer(sample)
    }

    override fun recordRender(sample: TrackingRenderSample) {
        offer(sample)
    }

    fun requestStop() {
        acceptingEvents.set(false)
    }

    override fun close() {
        requestStop()
        writerThread.join(CLOSE_TIMEOUT_MS)
        if (writerThread.isAlive) {
            Log.w(LOG_TAG, "Telemetry writer is still draining ${eventQueue.size} events")
        }
    }

    private fun offer(event: TrackingTelemetryEvent) {
        if (!acceptingEvents.get()) return
        if (SystemClock.uptimeMillis() < recordingStartsAtUptimeMs) return
        if (!eventQueue.offer(event)) {
            droppedEventCount.incrementAndGet()
        }
    }

    private fun writeEvents() {
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { output ->
                TrackingTelemetryCodec.writeHeader(output, header)
                while (acceptingEvents.get() || eventQueue.isNotEmpty()) {
                    val event = eventQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    TrackingTelemetryCodec.writeEvent(output, event)
                }
                TrackingTelemetryCodec.writeFooter(output, droppedEventCount.get())
                output.flush()
            }
            val session = BufferedInputStream(FileInputStream(outputFile)).use {
                TrackingTelemetryCodec.read(it)
            }
            val metrics = TrackingTelemetryAnalyzer.analyze(session)
            Log.i(LOG_TAG, "saved=${outputFile.absolutePath} bytes=${outputFile.length()}")
            Log.i(LOG_TAG, metrics.toLogLine())
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Tracking telemetry recording failed", error)
        }
    }

    companion object {
        const val EXTRA_ENABLED = "com.example.armakeup.extra.TRACKING_TELEMETRY"
        const val EXTRA_DURATION_MS = "com.example.armakeup.extra.TRACKING_DURATION_MS"
        const val EXTRA_WARMUP_MS = "com.example.armakeup.extra.TRACKING_WARMUP_MS"
        const val EXTRA_SCENARIO = "com.example.armakeup.extra.TRACKING_SCENARIO"
        private const val DEFAULT_DURATION_MS = 30_000L
        private const val DEFAULT_WARMUP_MS = 5_000L
        private const val MAXIMUM_WARMUP_MS = 30_000L
        private const val MAXIMUM_DURATION_MS = 10L * 60L * 1_000L
        private const val EVENT_QUEUE_CAPACITY = 512
        private const val QUEUE_POLL_MS = 100L
        private const val CLOSE_TIMEOUT_MS = 3_000L
        private const val WRITER_THREAD_NAME = "ar-makeup-tracking-recorder"
        private const val LOG_TAG = "ARMakeupTrackingV6"

        fun createIfRequested(
            context: Context,
            intent: Intent,
            debuggable: Boolean,
        ): TrackingTelemetryRecorder? {
            if (!debuggable || !intent.getBooleanExtra(EXTRA_ENABLED, false)) return null

            val scenario = intent.getStringExtra(EXTRA_SCENARIO)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_SCENARIO_LENGTH)
                ?: DEFAULT_SCENARIO
            val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
                .coerceIn(1_000L, MAXIMUM_DURATION_MS)
            val warmupMs = intent.getLongExtra(EXTRA_WARMUP_MS, DEFAULT_WARMUP_MS)
                .coerceIn(0L, MAXIMUM_WARMUP_MS)
            val directory = context.getExternalFilesDir(OUTPUT_DIRECTORY)
                ?: File(context.filesDir, OUTPUT_DIRECTORY)
            check(directory.exists() || directory.mkdirs()) {
                "Cannot create tracking telemetry directory ${directory.absolutePath}"
            }
            val timestamp = SimpleDateFormat(FILE_TIMESTAMP_FORMAT, Locale.US).format(Date())
            val safeScenario = scenario.replace(UNSAFE_FILE_CHARACTERS, "_")
            val outputFile = File(directory, "tracking-v6-$timestamp-$safeScenario.arv6")
            return TrackingTelemetryRecorder(outputFile, scenario, durationMs, warmupMs).also {
                Log.i(
                    LOG_TAG,
                    "recording scenario=$scenario warmupMs=$warmupMs durationMs=$durationMs " +
                        "path=${outputFile.absolutePath}",
                )
            }
        }

        private const val OUTPUT_DIRECTORY = "tracking-telemetry"
        private const val FILE_TIMESTAMP_FORMAT = "yyyyMMdd-HHmmss-SSS"
        private const val DEFAULT_SCENARIO = "baseline"
        private const val MAX_SCENARIO_LENGTH = 48
        private val UNSAFE_FILE_CHARACTERS = Regex("[^A-Za-z0-9._-]")
    }
}
