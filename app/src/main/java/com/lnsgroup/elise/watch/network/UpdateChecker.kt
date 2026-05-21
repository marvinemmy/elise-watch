package com.lnsgroup.elise.watch.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.lnsgroup.elise.watch.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateChecker"

object UpdateChecker {

    private const val VERSION_URL = "${BuildConfig.API_BASE_URL}/apk/version"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
                if (latestCode <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) return@launch

                Log.i(TAG, "Update available: v$latestCode > current ${BuildConfig.VERSION_CODE}")
                val apkBytes = downloadApk(apkUrl) ?: return@launch
                val apkFile = saveApk(context, apkBytes) ?: return@launch

                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
            }
        }
    }

    private fun downloadApk(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed: ${e.message}")
            null
        }
    }

    private fun saveApk(context: Context, bytes: ByteArray): File? {
        return try {
            val file = File(context.cacheDir, "elise-update.apk")
            file.writeBytes(bytes)
            file
        } catch (e: Exception) {
            Log.w(TAG, "APK save failed: ${e.message}")
            null
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Install intent failed: ${e.message}")
        }
    }
}
