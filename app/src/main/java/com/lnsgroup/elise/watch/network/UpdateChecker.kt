package com.lnsgroup.elise.watch.network

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.lnsgroup.elise.watch.BuildConfig
import com.lnsgroup.elise.watch.Config
import com.lnsgroup.elise.watch.service.OtaInstallReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateChecker"

object UpdateChecker {

    private const val VERSION_URL = "${Config.OTA_BASE_URL}/apk/version"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun checkAsync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(VERSION_URL).build()
                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    resp.body?.string() ?: return@launch
                }
                val json = JSONObject(body)
                val latestCode = json.optInt("version_code", 0)
                val apkUrl = json.optString("apk_url", "")
                if (latestCode <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) {
                    Log.d(TAG, "Already up to date: v${BuildConfig.VERSION_CODE} (server: v$latestCode)")
                    return@launch
                }

                Log.i(TAG, "Update: v${BuildConfig.VERSION_CODE} → v$latestCode — downloading")
                val apkBytes = downloadApk(apkUrl) ?: return@launch
                Log.i(TAG, "APK downloaded (${apkBytes.size}B) — installing via PackageInstaller")
                installViaPackageInstaller(context, apkBytes, latestCode)
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
            }
        }
    }

    private fun downloadApk(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "Download HTTP ${resp.code}"); null }
            else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "APK download failed: ${e.message}"); null
    }

    private fun installViaPackageInstaller(context: Context, apkBytes: ByteArray, versionCode: Int) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("elise.apk", 0, apkBytes.size.toLong()).use { out ->
                    out.write(apkBytes)
                    session.fsync(out)
                }
                val intent = Intent(context, OtaInstallReceiver::class.java).apply {
                    putExtra("version_code", versionCode)
                }
                val pi = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pi.intentSender)
            }
            Log.i(TAG, "PackageInstaller session committed for v1.0.$versionCode")
        } catch (e: Exception) {
            Log.e(TAG, "installViaPackageInstaller failed: ${e.message}")
        }
    }
}
