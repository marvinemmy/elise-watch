package com.lnsgroup.elise.watch.network

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "EliseConn"
internal const val CHANNEL_PATH_VOICE = "/elise/voice"
private const val HEALTH_URL = "https://lnsgroup.dev/health"

object EliseConnectionHelper {

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Teste si le serveur ÉLISE est directement accessible.
     * Retourne false si WiFi absent ou serveur injoignable.
     */
    suspend fun hasDirectInternet(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(HEALTH_URL).get().build()
            healthClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.d(TAG, "No direct internet: ${e.message}")
            false
        }
    }

    /**
     * Envoie l'audio au téléphone via Bluetooth (Wearable ChannelClient).
     * Le téléphone le relaie au serveur et renvoie la réponse.
     *
     * Protocole sur le channel "/elise/voice" :
     *   Watch → Phone : [4B token_len][token][4B wav_len][wav]
     *   Phone → Watch : [4B status=0][4B transcript_len][transcript][4B mp3_len][mp3]
     *                   ou [4B status=1][4B err_len][error_message]
     */
    suspend fun sendViaPhoneProxy(
        context: Context,
        wavBytes: ByteArray,
        token: String,
    ): EliseResponse = withContext(Dispatchers.IO) {
        // Trouver le nœud téléphone
        val nodes = Tasks.await(
            Wearable.getNodeClient(context).connectedNodes,
            6, TimeUnit.SECONDS
        )
        val phone = nodes.firstOrNull()
            ?: throw Exception("Téléphone non connecté via Bluetooth")

        Log.d(TAG, "Proxy via ${phone.displayName}")
        val channelClient = Wearable.getChannelClient(context)

        val channel = Tasks.await(
            channelClient.openChannel(phone.id, CHANNEL_PATH_VOICE),
            15, TimeUnit.SECONDS
        )

        try {
            // Écriture WAV vers le téléphone
            val os = Tasks.await(channelClient.getOutputStream(channel), 5, TimeUnit.SECONDS)
            DataOutputStream(os).use { out ->
                val tb = token.toByteArray(Charsets.UTF_8)
                out.writeInt(tb.size);  out.write(tb)
                out.writeInt(wavBytes.size); out.write(wavBytes)
                out.flush()
            }
            // Le close() de DataOutputStream signale EOF au téléphone sans fermer le channel

            // Lecture réponse depuis le téléphone (bloquant jusqu'à réponse ou timeout 60s)
            val inputStream = Tasks.await(channelClient.getInputStream(channel), 5, TimeUnit.SECONDS)
            DataInputStream(inputStream).use { din ->
                val status = din.readInt()
                if (status != 0) {
                    val errLen = din.readInt()
                    val errBytes = ByteArray(errLen).also { din.readFully(it) }
                    throw Exception("Proxy: ${String(errBytes, Charsets.UTF_8)}")
                }
                val transcriptLen = din.readInt()
                val transcriptBytes = ByteArray(transcriptLen).also { din.readFully(it) }
                val mp3Len = din.readInt()
                val mp3Bytes = if (mp3Len > 0) ByteArray(mp3Len).also { din.readFully(it) } else ByteArray(0)

                Log.d(TAG, "Proxy response: transcript='${String(transcriptBytes)}', mp3=${mp3Len}B")
                EliseResponse(mp3Bytes, String(transcriptBytes, Charsets.UTF_8), 0)
            }
        } finally {
            try { Tasks.await(channelClient.close(channel), 3, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
    }
}
