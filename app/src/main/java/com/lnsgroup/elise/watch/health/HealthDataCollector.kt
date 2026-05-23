package com.lnsgroup.elise.watch.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

data class HealthSnapshot(
    val heartRateBpm: Int = 0,
    val stepsSinceStart: Long = 0,
    val stressLevel: Float = 50f,    // 0 = calme, 100 = stressé
    val fatigueLevel: Float = 50f,   // 0 = frais, 100 = épuisé
    val agitationLevel: Float = 0f,  // 0 = serein, 100 = agité
    val activeMinutes: Int = 0,
    val socialScore: Int = 50,
    val scoreLabel: String = "—",
    val scoreColor: Int = 0xFF00E5FF.toInt(),
)

class HealthDataCollector(private val context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // HR history for HRV-based stress
    private val hrWindow = ArrayDeque<Float>(20)
    var lastHr = 0; private set

    // Steps
    private var stepBase = -1L
    private var stepsCurrent = 0L

    // Accelerometer for agitation (SENSOR_DELAY_GAME ≈ 50Hz)
    private val accWindow = ArrayDeque<Float>(60)

    // Active time tracking
    private var activeSeconds = 0
    private var lastTickMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_HEART_RATE -> {
                    val bpm = event.values[0]
                    if (bpm > 0 && event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                        lastHr = bpm.toInt()
                        hrWindow.addLast(bpm)
                        if (hrWindow.size > 20) hrWindow.removeFirst()
                        tickActive()
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val total = event.values[0].toLong()
                    if (stepBase < 0) stepBase = total
                    stepsCurrent = total - stepBase
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val rms = sqrt(
                        event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                    )
                    accWindow.addLast(rms)
                    if (accWindow.size > 60) accWindow.removeFirst()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        lastTickMs = System.currentTimeMillis()
        sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = try { sm.unregisterListener(listener) } catch (_: Exception) {}

    fun snapshot(): HealthSnapshot {
        val stress    = computeStress()
        val fatigue   = computeFatigue()
        val agitation = computeAgitation()
        val score     = SocialScoreCalculator.compute(lastHr, stress, agitation, fatigue, stepsCurrent)
        return HealthSnapshot(
            heartRateBpm   = lastHr,
            stepsSinceStart = stepsCurrent,
            stressLevel    = stress,
            fatigueLevel   = fatigue,
            agitationLevel = agitation,
            activeMinutes  = activeSeconds / 60,
            socialScore    = score,
            scoreLabel     = SocialScoreCalculator.label(score),
            scoreColor     = SocialScoreCalculator.color(score),
        )
    }

    private fun tickActive() {
        val now = System.currentTimeMillis()
        val elapsed = ((now - lastTickMs) / 1000L).coerceIn(0L, 30L).toInt()
        lastTickMs = now
        if (lastHr > 65 || (accWindow.lastOrNull() ?: 0f) > 0.8f) {
            activeSeconds = (activeSeconds + elapsed).coerceAtMost(86400)
        }
    }

    // Stress via coefficient de variation de la fréquence cardiaque (HRV proxy)
    private fun computeStress(): Float {
        if (hrWindow.size < 4) return 50f
        val mean = hrWindow.average().toFloat().takeIf { it > 0f } ?: return 50f
        val variance = hrWindow.map { (it - mean) * (it - mean) }.average().toFloat()
        val cv = sqrt(variance) / mean
        val base = when {
            cv >= 0.10f -> 8f
            cv >= 0.06f -> 26f
            cv >= 0.04f -> 48f
            cv >= 0.02f -> 67f
            else        -> 84f
        }
        val hrBonus = when {
            mean > 105f -> 18f
            mean > 90f  -> 10f
            mean > 78f  -> 4f
            else        -> 0f
        }
        return (base + hrBonus).coerceIn(0f, 100f)
    }

    // Fatigue inversement corrélée à l'activité (steps + HR repos bas)
    private fun computeFatigue(): Float {
        val stepFatigue = when {
            stepsCurrent > 8000 -> 8f
            stepsCurrent > 4000 -> 22f
            stepsCurrent > 1500 -> 40f
            stepsCurrent > 300  -> 58f
            else                -> 70f
        }
        val hrFatigue = if (lastHr in 1..53) 15f else 0f
        return (stepFatigue + hrFatigue).coerceIn(0f, 100f)
    }

    // Agitation via amplitude moyenne de l'accéléromètre
    private fun computeAgitation(): Float {
        if (accWindow.isEmpty()) return 0f
        val avg = accWindow.average().toFloat()
        return when {
            avg > 8f   -> 92f
            avg > 4f   -> 72f
            avg > 2f   -> 48f
            avg > 1f   -> 26f
            avg > 0.5f -> 10f
            else       -> 0f
        }
    }
}
