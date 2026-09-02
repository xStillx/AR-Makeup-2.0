package com.example.armakeup.arcore

/** Rejects repeated camera images before they consume conversion and MediaPipe inference work. */
internal class MonotonicSensorTimestampGate {
    private var lastAcceptedTimestampNs = Long.MIN_VALUE

    fun accept(timestampNs: Long): Boolean {
        if (timestampNs <= 0L || timestampNs <= lastAcceptedTimestampNs) return false
        lastAcceptedTimestampNs = timestampNs
        return true
    }

    fun reset() {
        lastAcceptedTimestampNs = Long.MIN_VALUE
    }
}
