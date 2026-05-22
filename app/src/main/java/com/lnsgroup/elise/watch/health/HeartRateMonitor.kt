package com.lnsgroup.elise.watch.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "HeartRateMonitor"

class HeartRateMonitor(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val _latestBpm = AtomicInteger(-1)

    val latestBpm: Int get() = _latestBpm.get()
    val isAvailable: Boolean get() = hrSensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isNotEmpty() && event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                val bpm = event.values[0].toInt()
                if (bpm > 0) {
                    _latestBpm.set(bpm)
                    Log.v(TAG, "HR: $bpm BPM")
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start() {
        if (hrSensor == null) {
            Log.w(TAG, "Heart rate sensor unavailable on this device")
            return
        }
        sensorManager.registerListener(listener, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.i(TAG, "Heart rate monitoring started")
    }

    fun stop() {
        try { sensorManager.unregisterListener(listener) } catch (_: Exception) {}
    }
}
