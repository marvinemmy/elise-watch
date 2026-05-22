package com.lnsgroup.elise.companion

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Channel
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

private const val TAG       = "EliseWatchProxy"
private const val PATH      = "/elise/voice"

/**
 * Service Wearable côté téléphone.
 * Quand la montre n'a pas d'internet (Bluetooth only), elle ouvre un channel
 * "/elise/voice" ici. Ce service :
 *   1. Lit le WAV envoyé par la montre
 *   2. Le relaie à EliseClient (WebSocket vers lnsgroup.dev)
 *   3. Renvoie la réponse MP3 + transcript à la montre
 */
class EliseWatchProxyService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onChannelOpened(channelClient: ChannelClient.Channel) {
        if (channelClient.path != PATH) return
        Log.d(TAG, "Watch audio channel opened from ${channelClient.nodeId}")
        scope.launch { handle(channelClient) }
    }

    private suspend fun handle(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        try {
            // Lire WAV + token depuis la montre
            val inputStream = Tasks.await(client.getInputStream(channel), 10, TimeUnit.SECONDS)
            val (token, wavBytes) = DataInputStream(inputStream).use { din ->
                val tokenLen = din.readInt()
                val tokenBytes = ByteArray(tokenLen).also { din.readFully(it) }
                val wavLen = din.readInt()
                val wavBytes = ByteArray(wavLen).also { din.readFully(it) }
                Pair(String(tokenBytes, Charsets.UTF_8), wavBytes)
            }
            Log.d(TAG, "Received ${wavBytes.size}B WAV from watch, relaying to server")

            // Relayer au serveur ÉLISE
            val response = try {
                EliseClient.sendVoice(wavBytes, token)
            } catch (e: Exception) {
                Log.e(TAG, "Relay failed: ${e.message}")
                sendError(client, channel, e.message ?: "Server error")
                return
            }

            // Renvoyer la réponse à la montre
            val os = Tasks.await(client.getOutputStream(channel), 5, TimeUnit.SECONDS)
            DataOutputStream(os).use { out ->
                out.writeInt(0) // status OK
                val tb = response.transcript.toByteArray(Charsets.UTF_8)
                out.writeInt(tb.size); out.write(tb)
                out.writeInt(response.mp3Bytes.size)
                if (response.mp3Bytes.isNotEmpty()) out.write(response.mp3Bytes)
                out.flush()
            }
            Log.d(TAG, "Proxy response sent: ${response.mp3Bytes.size}B MP3")

        } catch (e: Exception) {
            Log.e(TAG, "Proxy error: ${e.message}")
            try { sendError(client, channel, e.message ?: "Proxy error") } catch (_: Exception) {}
        } finally {
            try { Tasks.await(client.close(channel), 3, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
    }

    private suspend fun sendError(client: ChannelClient, channel: ChannelClient.Channel, msg: String) {
        try {
            val os = Tasks.await(client.getOutputStream(channel), 3, TimeUnit.SECONDS)
            DataOutputStream(os).use { out ->
                out.writeInt(1)
                val eb = msg.toByteArray(Charsets.UTF_8)
                out.writeInt(eb.size); out.write(eb)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
