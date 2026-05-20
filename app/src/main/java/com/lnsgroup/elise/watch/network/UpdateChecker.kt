package com.lnsgroup.elise.watch.network

import android.app.AlertDialog
import android.content.Context
import com.lnsgroup.elise.watch.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/marvinemmy/elise-watch/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun checkAsync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    resp.body?.string() ?: return@launch
                }
                val tag = JSONObject(body).optString("tag_name") // ex: "v1.0.42"
                if (tag.isBlank()) return@launch
                val latestCode = tag.removePrefix("v").split(".").lastOrNull()?.toIntOrNull()
                    ?: return@launch
                if (latestCode > BuildConfig.VERSION_CODE) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(context)
                            .setTitle("Mise à jour Élise disponible")
                            .setMessage(
                                "Version $tag disponible (actuelle : ${BuildConfig.VERSION_NAME})\n\n" +
                                "Télécharger sur GitHub puis :\n" +
                                "adb connect <IP_MONTRE>:5555\n" +
                                "adb install -r ELISE_Watch_$tag.apk"
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
