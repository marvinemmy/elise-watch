package com.lnsgroup.elise.watch.service

import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

private const val TAG = "WifiSyncListener"
private const val WIFI_SYNC_PATH = "/wifi/sync"

class WifiSyncListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WIFI_SYNC_PATH) return
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val ssid = json.getString("ssid")
            val password = json.optString("password", "")
            val type = json.optString("type", "WPA")
            Log.i(TAG, "Received WiFi credentials for: $ssid ($type)")
            addNetwork(ssid, password, type)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun addNetwork(ssid: String, password: String, type: String) {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                when (type.uppercase()) {
                    "WPA", "WPA2", "WPA3" -> setWpa2Passphrase(password)
                    "WEP" -> {} // WEP not supported in suggestions API — skip passphrase
                    // NOPASS → open network, no passphrase needed
                }
            }
            .setIsAppInteractionRequired(false)
            .build()

        val result = wm.addNetworkSuggestions(listOf(suggestion))
        when (result) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                Log.i(TAG, "Network suggestion added: $ssid")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                Log.i(TAG, "Network already known: $ssid")
            else ->
                Log.w(TAG, "addNetworkSuggestions result=$result for $ssid")
        }
    }
}
