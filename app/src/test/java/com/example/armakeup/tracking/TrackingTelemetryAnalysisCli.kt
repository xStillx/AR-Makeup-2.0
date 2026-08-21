package com.example.armakeup.tracking

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/** Local-only analyzer for exact device recordings; this class is never packaged in the APK. */
object TrackingTelemetryAnalysisCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) {
            "Expected exactly one .arv6 path"
        }
        val recording = File(arguments.single()).absoluteFile
        require(recording.isFile) {
            "Telemetry recording does not exist: ${recording.path}"
        }
        val session = BufferedInputStream(FileInputStream(recording)).use {
            TrackingTelemetryCodec.read(it)
        }
        println(TrackingTelemetryAnalyzer.analyze(session).toLogLine())
    }
}
