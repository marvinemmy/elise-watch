package com.lnsgroup.elise.companion

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "EliseClient"

data class EliseResponse(val transcript: String, val responseText: String, val mp3Bytes: ByteArray)

object EliseClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Protocole serveur :
     * → binary WAV bytes (complet)
     * → JSON {"type":"end"}
     * ← JSON {"type":"ready"}
     * ← JSON {"type":"processing"}
     * ← JSON {"type":"response_start","transcript":"...","size":N}
     * ← binary MP3 chunks
     * ← JSON {"type":"response_end"}
     * [connexion fermée par serveur]
     */
    suspend fun sendVoice(wavBytes: ByteArray, token: String): EliseResponse =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}/ws/voice?token=$token")
                .build()

            var transcript = ""
            var responseText = ""
            val mp3Buf = mutableListOf<Byte>()

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Envoie le WAV complet puis signal de fin
                    webSocket.send(wavBytes.toByteString())
                    webSocket.send("""{"type":"end"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "ready"           -> { /* serveur prêt */ }
                            "processing"      -> { /* traitement en cours */ }
                            "response_start"  -> {
                                // Le serveur envoie transcript + taille MP3 ici
                                transcript = json.optString("transcript", "")
                                responseText = transcript
                            }
                            "response_end"    -> {
                                // Toutes les données reçues — fermer proprement
                                webSocket.close(1000, "done")
                            }
                            "error" -> {
                                webSocket.close(1000, "error")
                                if (!cont.isCompleted)
                                    cont.resumeWithException(Exception(json.optString("message")))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON parse error: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Chunks MP3
                    mp3Buf.addAll(bytes.toByteArray().toList())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!cont.isCompleted)
                        cont.resume(EliseResponse(transcript, responseText, mp3Buf.toByteArray()))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!cont.isCompleted) cont.resumeWithException(t)
                }
            })

            cont.invokeOnCancellation { ws.cancel() }
        }

    fun pcmToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val bb = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + pcm.size)
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(pcm.size); bb.put(pcm)
        return bb.array()
    }
}
