package com.lnsgroup.elise.companion

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun checkAsync(context: Context, onStatus: (String) -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = doCheck(context)
            withContext(Dispatchers.Main) { onStatus(result) }
        }
    }

    fun checkNow(context: Context, onDone: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = doCheck(context)
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    private suspend fun doCheck(context: Context): String {
        return try {
            val versionUrl = "${BuildConfig.API_BASE_URL}/apk/companion/version"
            val req = Request.Builder().url(versionUrl).build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Serveur inaccessible (${resp.code})"
                resp.body?.string() ?: return "Réponse vide"
            }
            val json = JSONObject(body)
            val latestCode = json.optInt("version_code", 0)
            val apkUrl = json.optString("apk_url", "")

            if (latestCode <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) {
                return "À jour (v${BuildConfig.VERSION_NAME})"
            }

            Log.i(TAG, "Mise à jour disponible: v$latestCode > ${BuildConfig.VERSION_CODE}")
            val apkBytes = download(apkUrl) ?: return "Échec du téléchargement"
            val apkFile = save(context, apkBytes) ?: return "Échec de la sauvegarde"

            withContext(Dispatchers.Main) { install(context, apkFile) }
            "Installation de la v1.0.$latestCode…"
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            "Vérification échouée: ${e.message?.take(50)}"
        }
    }

    private fun download(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Download failed: ${e.message}")
        null
    }

    private fun save(context: Context, bytes: ByteArray): File? = try {
        val file = File(context.cacheDir, "elise-companion-update.apk")
        file.writeBytes(bytes)
        file
    } catch (e: Exception) {
        null
    }

    private fun install(context: Context, apkFile: File) {
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
