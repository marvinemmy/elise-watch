package com.lnsgroup.elise.watch.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "EliseWebSocket"

data class EliseResponse(
    val mp3Bytes: ByteArray,
    val transcript: String,
    val responseMs: Int,
)

class EliseWebSocket(private val serverUrl: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Envoie de l'audio WAV et attend la réponse MP3 d'ÉLISE.
     * Protocole :
     *   → binary WAV chunks (8KB max par chunk)
     *   → JSON {"type":"end"}
     *   ← JSON {"type":"ready"}
     *   ← JSON {"type":"processing"}
     *   ← JSON {"type":"response_start","transcript":"...","size":N}
     *   ← binary MP3 chunks
     *   ← JSON {"type":"response_end"}
     */
    suspend fun sendVoice(wavBytes: ByteArray): EliseResponse = withContext(Dispatchers.IO) {
        val url = "$serverUrl?token=${token}"
        val request = Request.Builder().url(url).build()

        suspendCancellableCoroutine { cont ->
            val mp3Buffer = mutableListOf<ByteArray>()
            var transcript = ""
            var responseMs = 0
            var expectedSize = 0
            var audioStarted = false
            var audioSent = false

            val ws = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val evt = JSONObject(text)
                    when (evt.optString("type")) {
                        "ready" -> {
                            // Envoyer l'audio en chunks de 8KB
                            if (!audioSent) {
                                audioSent = true
                                val chunkSize = 8192
                                for (i in wavBytes.indices step chunkSize) {
                                    val chunk = wavBytes.copyOfRange(i, minOf(i + chunkSize, wavBytes.size))
                                    ws.send(okio.ByteString.of(*chunk))
                                }
                                ws.send("""{"type":"end"}""")
                                Log.d(TAG, "Audio sent: ${wavBytes.size} bytes")
                            }
                        }
                        "processing" -> Log.d(TAG, "Server processing...")
                        "response_start" -> {
                            transcript = evt.optString("transcript", "")
                            responseMs = evt.optInt("response_ms", 0)
                            expectedSize = evt.optInt("size", 0)
                            audioStarted = true
                            Log.d(TAG, "Response incoming: $expectedSize bytes, transcript='$transcript'")
                        }
                        "response_end" -> {
                            val mp3 = mp3Buffer.fold(ByteArray(0)) { acc, b -> acc + b }
                            ws.close(1000, null)
                            Log.d(TAG, "Response complete: ${mp3.size} bytes MP3")
                            if (cont.isActive) cont.resume(
                                EliseResponse(mp3, transcript, responseMs)
                            )
                        }
                        "error" -> {
                            val msg = evt.optString("message", "Unknown error")
                            Log.e(TAG, "Server error: $msg")
                            ws.close(1000, null)
                            if (cont.isActive) cont.resumeWithException(Exception(msg))
                        }
                        "pong" -> {}
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                    if (audioStarted) {
                        mp3Buffer.add(bytes.toByteArray())
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    if (cont.isActive) cont.resumeWithException(t)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                }
            })

            cont.invokeOnCancellation {
                ws.cancel()
            }
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
    }
}
