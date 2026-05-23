package com.lnsgroup.elise.watch.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

private const val TAG = "WatchRoutineScheduler"
private const val PREFS = "watch_routines"

object WatchRoutineScheduler {

    const val ROUTINE_MORNING       = "morning_report"
    const val ROUTINE_EVENING_CHECK = "evening_check"
    const val ROUTINE_FUEL_EVENING  = "fuel_evening"
    const val ROUTINE_SHOPPING      = "shopping_reminder"

    fun scheduleAll(context: Context) {
        listOf(ROUTINE_MORNING, ROUTINE_EVENING_CHECK, ROUTINE_FUEL_EVENING, ROUTINE_SHOPPING)
            .forEach { schedule(context, it) }
        Log.i(TAG, "All watch routines scheduled")
    }

    fun schedule(context: Context, routineId: String) {
        val fireAt = nextFireTime(context, routineId) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, routineId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
            Log.i(TAG, "Scheduled $routineId at ${java.util.Date(fireAt)}")
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
    }

    private fun nextFireTime(context: Context, routineId: String): Long? {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance()

        when (routineId) {
            ROUTINE_MORNING -> {
                val isWeekend = now.get(Calendar.DAY_OF_WEEK).let {
                    it == Calendar.SATURDAY || it == Calendar.SUNDAY
                }
                val hour = if (isWeekend) 8 else 7
                val minute = 30
                next.set(Calendar.HOUR_OF_DAY, hour)
                next.set(Calendar.MINUTE, minute)
                next.set(Calendar.SECOND, 0)
                next.set(Calendar.MILLISECOND, 0)
                if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
                // Recompute hour for the actual next day
                val nextDay = next.get(Calendar.DAY_OF_WEEK)
                val nextIsWeekend = nextDay == Calendar.SATURDAY || nextDay == Calendar.SUNDAY
                next.set(Calendar.HOUR_OF_DAY, if (nextIsWeekend) 8 else 7)
            }

            ROUTINE_EVENING_CHECK -> {
                if (isVacation(context)) return null
                next.set(Calendar.HOUR_OF_DAY, 21)
                next.set(Calendar.MINUTE, 0)
                next.set(Calendar.SECOND, 0)
                next.set(Calendar.MILLISECOND, 0)
                if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
            }

            ROUTINE_FUEL_EVENING -> {
                // Wed and Thu at 20:30 only
                next.set(Calendar.HOUR_OF_DAY, 20)
                next.set(Calendar.MINUTE, 30)
                next.set(Calendar.SECOND, 0)
                next.set(Calendar.MILLISECOND, 0)
                if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
                var tries = 0
                while (tries < 7) {
                    val dow = next.get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.WEDNESDAY || dow == Calendar.THURSDAY) break
                    next.add(Calendar.DAY_OF_YEAR, 1)
                    tries++
                }
                if (tries == 7) return null
            }

            ROUTINE_SHOPPING -> {
                // Sat 10:00 if not working, else Sun 10:00
                next.set(Calendar.HOUR_OF_DAY, 10)
                next.set(Calendar.MINUTE, 0)
                next.set(Calendar.SECOND, 0)
                next.set(Calendar.MILLISECOND, 0)
                if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
                var tries = 0
                while (tries < 7) {
                    val dow = next.get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) break
                    next.add(Calendar.DAY_OF_YEAR, 1)
                    tries++
                }
                if (tries == 7) return null
            }

            else -> return null
        }
        return next.timeInMillis
    }

    private fun pendingIntent(context: Context, routineId: String): PendingIntent {
        val intent = Intent(context, WatchRoutineReceiver::class.java).apply {
            action = "com.lnsgroup.elise.watch.ROUTINE"
            putExtra("routine_id", routineId)
        }
        val requestCode = routineId.hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── SharedPreferences helpers ────────────────────────────────────────────

    fun isVacation(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("vacation", false)

    fun setVacation(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("vacation", v).apply()

    fun needsFuel(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("needs_fuel", false)

    fun setNeedsFuel(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("needs_fuel", v).apply()

    fun isWorkDay(ctx: Context, dayOfWeek: Int): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean("work_${dayOfWeek}", dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY)
    }

    fun getTomorrowWork(ctx: Context): Boolean? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains("tomorrow_work")) prefs.getBoolean("tomorrow_work", true) else null
    }

    fun setTomorrowWork(ctx: Context, work: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("tomorrow_work", work).apply()
}
