package com.lnsgroup.elise.companion

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "WatchOta"
private const val OTA_CHANNEL = "/elise/ota"
private const val PREFS = "watch_ota"
private const val KEY_LAST_PUSHED = "last_pushed_version"

object WatchOtaManager {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun checkAndPush(context: Context, onStatus: (String) -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = doCheckAndPush(context)
            withContext(Dispatchers.Main) { onStatus(result) }
        }
    }

    private suspend fun doCheckAndPush(context: Context): String {
        return try {
            // 1. Vérifier la dernière version disponible sur le serveur
            val req = Request.Builder().url("${BuildConfig.API_BASE_URL}/apk/version").build()
            val json = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Serveur inaccessible (${resp.code})"
                JSONObject(resp.body?.string() ?: return "Réponse vide")
            }
            val serverVersion = json.optInt("version_code", 0)
            val apkUrl = json.optString("apk_url", "")

            if (serverVersion == 0 || apkUrl.isBlank()) return "Aucun build disponible"

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastPushed = prefs.getInt(KEY_LAST_PUSHED, 0)

            if (serverVersion <= lastPushed) {
                Log.d(TAG, "Watch up to date v1.0.$serverVersion")
                return "Montre à jour (v1.0.$serverVersion)"
            }

            Log.i(TAG, "Nouvelle version montre: v1.0.$serverVersion (dernière poussée: $lastPushed)")

            // 2. Télécharger l'APK
            val apkBytes = download(apkUrl) ?: return "Échec du téléchargement"
            Log.i(TAG, "APK téléchargé: ${apkBytes.size}B")

            // 3. Trouver la montre connectée
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, 10, TimeUnit.SECONDS
            )
            val watch = nodes.firstOrNull()
                ?: return "Montre non connectée (v1.0.$serverVersion disponible, sera poussée à la prochaine connexion)"

            // 4. Envoyer l'APK à la montre via Wearable Channel
            val channelClient = Wearable.getChannelClient(context)
            val channel = Tasks.await(
                channelClient.openChannel(watch.id, OTA_CHANNEL), 20, TimeUnit.SECONDS
            )
            try {
                val os = Tasks.await(channelClient.getOutputStream(channel), 5, TimeUnit.SECONDS)
                DataOutputStream(os).use { out ->
                    out.writeInt(serverVersion)
                    out.writeInt(apkBytes.size)
                    out.write(apkBytes)
                    out.flush()
                }
                prefs.edit().putInt(KEY_LAST_PUSHED, serverVersion).apply()
                Log.i(TAG, "APK envoyé à la montre: v1.0.$serverVersion")
                "Mise à jour montre envoyée: v1.0.$serverVersion"
            } finally {
                try { Tasks.await(channelClient.close(channel), 3, TimeUnit.SECONDS) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA échoué: ${e.message}")
            "OTA: ${e.message?.take(60)}"
        }
    }

    private fun download(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Download failed: ${e.message}")
        null
    }
}
