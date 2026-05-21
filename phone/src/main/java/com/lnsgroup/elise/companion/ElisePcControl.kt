package com.lnsgroup.elise.companion

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ElisePcControl"

/**
 * Contrôle à distance du PC via l'API serveur.
 * Commandes : bat_mode_on/off/toggle, camera_on/off, mic_on/off, shutdown, wake
 */
object ElisePcControl {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun sendCommand(command: String, params: Map<String, Any> = emptyMap()): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("command", command)
                    params.forEach { (k, v) -> put(k, v) }
                }
                val req = Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/admin/pc/command")
                    .header("Authorization", "Bearer $TOKEN")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                val txt = resp.body?.string() ?: ""
                if (resp.isSuccessful) Result.success(txt)
                else Result.failure(Exception("HTTP ${resp.code}: $txt"))
            } catch (e: Exception) {
                Log.e(TAG, "Command $command failed: ${e.message}")
                Result.failure(e)
            }
        }

    suspend fun pcStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}/admin/pc/status")
                .header("Authorization", "Bearer $TOKEN")
                .get().build()
            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            json.optBoolean("online", false)
        } catch (e: Exception) { false }
    }

    suspend fun wakePC(macAddress: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("mac", macAddress) }
            val req = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}/admin/pc/wake")
                .header("Authorization", "Bearer $TOKEN")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val txt = resp.body?.string() ?: ""
            if (resp.isSuccessful) Result.success(txt) else Result.failure(Exception(txt))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
