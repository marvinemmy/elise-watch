package com.lnsgroup.elise.watch.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.util.concurrent.TimeUnit

private const val TAG = "EliseOta"
private const val OTA_CHANNEL = "/elise/ota"

class EliseOtaService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != OTA_CHANNEL) return
        Log.i(TAG, "OTA channel reçu depuis ${channel.nodeId}")
        scope.launch { receiveAndInstall(channel) }
    }

    private suspend fun receiveAndInstall(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        try {
            val inputStream = Tasks.await(client.getInputStream(channel), 10, TimeUnit.SECONDS)
            DataInputStream(inputStream).use { din ->
                val versionCode = din.readInt()
                val apkLen = din.readInt()
                val apkBytes = ByteArray(apkLen).also { din.readFully(it) }
                Log.i(TAG, "APK reçu v1.0.$versionCode (${apkLen}B) — installation en cours")
                installApk(apkBytes, versionCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTA receive failed: ${e.message}")
        } finally {
            try { Tasks.await(client.close(channel), 3, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
    }

    private fun installApk(apkBytes: ByteArray, versionCode: Int) {
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("elise-watch.apk", 0, apkBytes.size.toLong()).use { out ->
                    out.write(apkBytes)
                    session.fsync(out)
                }
                val intent = Intent(this, OtaInstallReceiver::class.java).apply {
                    putExtra("version_code", versionCode)
                }
                val pi = PendingIntent.getBroadcast(
                    this, sessionId, intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pi.intentSender)
            }
            Log.i(TAG, "PackageInstaller session committed for v1.0.$versionCode")
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
