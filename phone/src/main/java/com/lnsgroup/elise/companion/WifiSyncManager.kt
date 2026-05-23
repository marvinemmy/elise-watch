package com.lnsgroup.elise.companion

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "WifiSyncManager"
const val WIFI_SYNC_PATH = "/wifi/sync"
private const val PREFS = "wifi_credentials"

object WifiSyncManager {

    /** SSID actuellement connecté sur le téléphone. */
    fun getCurrentSsid(ctx: Context): String? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return null
        val raw = info.ssid ?: return null
        return if (raw.startsWith("\"") && raw.endsWith("\"")) raw.drop(1).dropLast(1) else raw
    }

    /** Parse le contenu d'un QR code WiFi : WIFI:T:WPA;S:ssid;P:password;; */
    fun parseWifiQr(content: String): Triple<String, String, String>? {
        if (!content.startsWith("WIFI:")) return null
        fun extract(key: String): String {
            val pattern = Regex("$key:([^;]*)")
            return pattern.find(content)?.groupValues?.get(1) ?: ""
        }
        val ssid = extract("S").ifBlank { return null }
        val password = extract("P")
        val type = extract("T").ifBlank { "WPA" }
        return Triple(ssid, password, type)
    }

    /** Stocke les credentials localement pour ce réseau. */
    fun saveNetwork(ctx: Context, ssid: String, password: String, type: String = "WPA") {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pwd_$ssid", password)
            .putString("type_$ssid", type)
            .apply()
        Log.i(TAG, "Saved network: $ssid")
    }

    fun getSavedPassword(ctx: Context, ssid: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pwd_$ssid", null)

    /** Envoie les credentials WiFi à la montre via Wearable MessageClient. */
    suspend fun pushToWatch(ctx: Context, ssid: String, password: String, type: String = "WPA"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("ssid", ssid)
                    put("password", password)
                    put("type", type)
                }.toString().toByteArray(Charsets.UTF_8)

                val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes)
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No watch nodes connected")
                    return@withContext false
                }
                nodes.forEach { node ->
                    Tasks.await(
                        Wearable.getMessageClient(ctx)
                            .sendMessage(node.id, WIFI_SYNC_PATH, payload)
                    )
                    Log.i(TAG, "WiFi pushed to watch ${node.displayName}: $ssid")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Push failed: ${e.message}")
                false
            }
        }
}
