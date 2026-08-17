package com.example.armakeup.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log

/** Lifecycle-aware, non-batched gyroscope source for render-time camera-motion correction. */
internal class AndroidGyroscopeSource(
    context: Context,
    private val history: GyroscopeRotationHistory,
    private val handler: Handler,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var registered = false

    val isAvailable: Boolean
        get() = sensor != null

    fun start() {
        if (registered) return
        history.clear()
        val activeSensor = sensor ?: run {
            Log.w(LOG_TAG, "No gyroscope is available; V6.3 correction is disabled")
            return
        }
        registered = sensorManager?.registerListener(
            this,
            activeSensor,
            REQUESTED_SAMPLE_PERIOD_US,
            MAX_REPORT_LATENCY_US,
            handler,
        ) == true
        Log.i(
            LOG_TAG,
            "gyro=${activeSensor.name} type=${activeSensor.type} " +
                "minDelayUs=${activeSensor.minDelay} registered=$registered",
        )
    }

    fun stop() {
        if (registered) sensorManager?.unregisterListener(this, sensor)
        registered = false
        history.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.sensor != sensor || event.values.size < AXIS_COUNT) return
        val uncalibrated = event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED &&
            event.values.size >= UNCALIBRATED_VALUE_COUNT
        val x = removeEstimatedBias(event.values, X_AXIS, uncalibrated)
        val y = removeEstimatedBias(event.values, Y_AXIS, uncalibrated)
        val z = removeEstimatedBias(event.values, Z_AXIS, uncalibrated)
        history.addSample(event.timestamp, x, y, z)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor == this.sensor && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            history.clear()
        }
    }

    private fun removeEstimatedBias(values: FloatArray, axis: Int, uncalibrated: Boolean): Float {
        if (!uncalibrated) return values[axis]
        val bias = values[axis + BIAS_OFFSET]
        return if (bias.isFinite()) values[axis] - bias else values[axis]
    }

    companion object {
        private const val REQUESTED_SAMPLE_PERIOD_US = 5_000
        private const val MAX_REPORT_LATENCY_US = 0
        private const val AXIS_COUNT = 3
        private const val UNCALIBRATED_VALUE_COUNT = 6
        private const val BIAS_OFFSET = 3
        private const val X_AXIS = 0
        private const val Y_AXIS = 1
        private const val Z_AXIS = 2
        private const val LOG_TAG = "ARMakeupGyroV6"
    }
}
