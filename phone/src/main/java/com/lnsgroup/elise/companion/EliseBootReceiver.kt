package com.lnsgroup.elise.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class EliseBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Settings.canDrawOverlays(ctx)) {
                EliseOverlayService.start(ctx)
                EliseCallMonitor.start(ctx)
            }
        }
    }
}
