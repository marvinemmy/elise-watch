package com.lnsgroup.elise.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

private const val TAG = "OtaInstall"

class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val version = intent.getIntExtra("version_code", 0)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // L'utilisateur doit confirmer l'installation sur la montre
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmIntent != null) {
                    Log.i(TAG, "Confirmation requise pour v1.0.$version")
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "OTA v1.0.$version installée avec succès!")
            else ->
                Log.e(TAG, "OTA install échouée: status=$status msg=$msg")
        }
    }
}
