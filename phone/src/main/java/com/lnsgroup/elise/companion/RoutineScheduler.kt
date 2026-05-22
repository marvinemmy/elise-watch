package com.lnsgroup.elise.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

private const val TAG = "RoutineScheduler"

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getStringExtra("routine_id") ?: return
        Log.i(TAG, "Alarm fired: $routineId")
        RoutineExecutor.execute(context, routineId)
        // Reschedule for next occurrence
        RoutineScheduler.schedule(context, routineId)
    }
}

object RoutineScheduler {

    const val ROUTINE_MORNING      = "morning_report"
    const val ROUTINE_DEPARTURE    = "departure_checklist"
    const val ROUTINE_EVENING_CHECK = "evening_check"
    const val ROUTINE_FUEL_EVENING  = "fuel_evening"
    const val ROUTINE_SHOPPING      = "shopping_reminder"

    private val ALL = listOf(
        ROUTINE_MORNING, ROUTINE_EVENING_CHECK, ROUTINE_FUEL_EVENING, ROUTINE_SHOPPING
    )

    fun scheduleAll(context: Context) {
        ALL.forEach { schedule(context, it) }
        Log.i(TAG, "All routines scheduled")
    }

    fun schedule(context: Context, routineId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextFire = nextFireTime(routineId) ?: return
        val pi = pendingIntent(context, routineId)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFire, pi)
            Log.d(TAG, "Scheduled $routineId at ${java.util.Date(nextFire)}")
        } catch (e: SecurityException) {
            // API 31+: needs SCHEDULE_EXACT_ALARM permission; fall back to inexact
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextFire, pi)
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ALL.forEach { am.cancel(pendingIntent(context, it)) }
    }

    private fun pendingIntent(context: Context, routineId: String): PendingIntent {
        val intent = Intent(context, RoutineReceiver::class.java).apply {
            putExtra("routine_id", routineId)
        }
        val reqCode = routineId.hashCode() and 0xFFFF
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextFireTime(routineId: String): Long? {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance()

        return when (routineId) {

            ROUTINE_MORNING -> {
                // 7:30 Mon-Fri, 8:30 Sat-Sun
                val dow = now.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
                cal.set(Calendar.HOUR_OF_DAY, if (isWeekend) 8 else 7)
                cal.set(Calendar.MINUTE, 30)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
                // Adjust hour for next day
                val nextDow = cal.get(Calendar.DAY_OF_WEEK)
                val nextIsWeekend = nextDow == Calendar.SATURDAY || nextDow == Calendar.SUNDAY
                cal.set(Calendar.HOUR_OF_DAY, if (nextIsWeekend) 8 else 7)
                cal.timeInMillis
            }

            ROUTINE_EVENING_CHECK -> {
                // 21:00 every night
                cal.set(Calendar.HOUR_OF_DAY, 21)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.timeInMillis
            }

            ROUTINE_FUEL_EVENING -> {
                // Wednesday and Thursday at 20:30
                cal.set(Calendar.HOUR_OF_DAY, 20)
                cal.set(Calendar.MINUTE, 30)
                cal.set(Calendar.SECOND, 0)
                // Find next Wednesday or Thursday
                val target = cal.clone() as Calendar
                for (i in 0..6) {
                    val testCal = cal.clone() as Calendar
                    testCal.add(Calendar.DAY_OF_YEAR, i)
                    testCal.set(Calendar.HOUR_OF_DAY, 20)
                    testCal.set(Calendar.MINUTE, 30)
                    testCal.set(Calendar.SECOND, 0)
                    val d = testCal.get(Calendar.DAY_OF_WEEK)
                    if ((d == Calendar.WEDNESDAY || d == Calendar.THURSDAY) && testCal.timeInMillis > now.timeInMillis) {
                        return testCal.timeInMillis
                    }
                }
                null
            }

            ROUTINE_SHOPPING -> {
                // Saturday 10:00 (or Sunday 10:00 if working Saturday)
                cal.set(Calendar.HOUR_OF_DAY, 10)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                for (i in 0..6) {
                    val testCal = cal.clone() as Calendar
                    testCal.add(Calendar.DAY_OF_YEAR, i)
                    testCal.set(Calendar.HOUR_OF_DAY, 10)
                    testCal.set(Calendar.MINUTE, 0)
                    testCal.set(Calendar.SECOND, 0)
                    val d = testCal.get(Calendar.DAY_OF_WEEK)
                    if ((d == Calendar.SATURDAY || d == Calendar.SUNDAY) && testCal.timeInMillis > now.timeInMillis) {
                        return testCal.timeInMillis
                    }
                }
                null
            }

            else -> null
        }
    }
}
