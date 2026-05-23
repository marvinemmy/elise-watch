package com.lnsgroup.elise.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "WatchRoutineReceiver"

class WatchRoutineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getStringExtra("routine_id") ?: return
        Log.i(TAG, "Routine fired: $routineId")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                WatchRoutineExecutor.execute(context, routineId)
            } finally {
                // Reschedule for next occurrence
                WatchRoutineScheduler.schedule(context, routineId)
                pending.finish()
            }
        }
    }
}
