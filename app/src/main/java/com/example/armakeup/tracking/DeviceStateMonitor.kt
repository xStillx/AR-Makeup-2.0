package com.example.armakeup.tracking

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Low-rate public thermal/battery snapshot for V6.2 correlation telemetry. */
internal class DeviceStateMonitor(context: Context) {
    private val applicationContext = context.applicationContext
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private var lastReadUptimeMs = Long.MIN_VALUE
    private var latest = TrackingDeviceState.UNKNOWN

    fun snapshot(nowUptimeMs: Long = SystemClock.uptimeMillis()): TrackingDeviceState {
        if (
            lastReadUptimeMs != Long.MIN_VALUE &&
            nowUptimeMs - lastReadUptimeMs < MINIMUM_READ_INTERVAL_MS
        ) {
            return latest
        }
        lastReadUptimeMs = nowUptimeMs
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            -1
        }
        latest = TrackingDeviceState(
            thermalStatus = thermalStatus,
            batteryTemperatureCelsius = readBatteryTemperatureCelsius(),
        )
        return latest
    }

    @Suppress("DEPRECATION")
    private fun readBatteryTemperatureCelsius(): Float {
        val batteryIntent: Intent = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return Float.NaN
        val tenthsCelsius = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE,
            Int.MIN_VALUE,
        )
        return if (tenthsCelsius == Int.MIN_VALUE) Float.NaN else tenthsCelsius / 10f
    }

    companion object {
        private const val MINIMUM_READ_INTERVAL_MS = 1_000L
    }
}
