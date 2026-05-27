package com.lnsgroup.elise.companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

private const val TAG = "EliseRTCClient"

/**
 * Événements de contrôle reçus via DataChannel depuis le serveur ÉLISE.
 * Même protocole que WebSocket : processing / response_start / response_end.
 */
data class RtcEvent(
    val type:       String,
    val transcript: String = "",
    val sttMs:      Int    = 0,
    val totalMs:    Int    = 0,
)

/**
 * Client WebRTC pour ÉLISE — transport UDP/DTLS/SRTP.
 *
 * Architecture :
 *   [Mic] → WebRTC Opus → UDP → serveur → VAD → STT → LLM → TTS
 *         ← WebRTC Opus ← UDP ← aiortc ← edge-tts PCM48k
 *   [DataChannel] ← JSON : processing / response_start / response_end
 *
 * Utilisation :
 *   val client = EliseRTCClient(context, token)
 *   client.connect()                          // crée PeerConnection + offre SDP
 *   for (evt in client.events) { ... }        // écoute les événements
 *   client.sendInterrupt()                    // interrompre la réponse en cours
 *   client.disconnect()                       // fermer proprement
 */
class EliseRTCClient(private val context: Context, private val token: String) {

    /** Canal d'événements de contrôle — consommer dans une coroutine. */
    val events = Channel<RtcEvent>(Channel.UNLIMITED)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var factory        : PeerConnectionFactory? = null
    private var pc             : PeerConnection?        = null
    private var dc             : DataChannel?           = null
    private var sessionId      : String?                = null
    private var audioSource    : AudioSource?           = null
    private var audioTrack     : AudioTrack?            = null
    private val iceGatheringDone = CompletableDeferred<Unit>()

    // ── ICE servers (miroir de la config serveur) ────────────────────────────

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    )

    private val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics           = PeerConnection.SdpSemantics.UNIFIED_PLAN
        // GATHER_ONCE = rassemble tous les candidats une fois, puis envoie l'offre
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        enableDtlsSrtp         = true
    }

    // ── Connexion principale ─────────────────────────────────────────────────

    /**
     * Établit la connexion WebRTC avec le serveur ÉLISE.
     * Bloque jusqu'à ce que le canal audio soit ouvert.
     * Lance une exception en cas d'échec.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        initFactory()
        buildPeerConnection()
        addLocalAudioTrack()
        createDataChannel()

        // Crée l'offre SDP et attend que ICE gathering soit terminé
        // avant de l'envoyer (vanilla ICE — pas de trickle nécessaire)
        val offer = createOffer()
        setLocalDescription(offer)
        withTimeoutOrNull(4_000L) { iceGatheringDone.await() }

        // Envoie l'offre au serveur, récupère la réponse SDP
        val localDesc = pc!!.localDescription ?: error("No local description after ICE")
        val answerJson = postOffer(localDesc.description, localDesc.type.canonicalForm())
        sessionId = answerJson.getString("session_id")

        val answer = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answerJson.getString("type")),
            answerJson.getString("sdp"),
        )
        setRemoteDescription(answer)

        Log.i(TAG, "WebRTC connected — session=$sessionId")
    }

    // ── Commandes ─────────────────────────────────────────────────────────────

    /** Interrompt la réponse en cours d'ÉLISE. */
    fun sendInterrupt() {
        if (dc?.state() == DataChannel.State.OPEN) {
            val bytes = """{"type":"interrupt"}""".toByteArray()
            dc!!.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
        }
    }

    /** Ferme la session WebRTC proprement côté client et serveur. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        sessionId?.let { sid ->
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url("${BuildConfig.API_BASE_URL}/rtc/session/$sid")
                        .delete().build()
                ).execute().close()
            }
        }
        dc?.close(); dc?.dispose(); dc = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        pc?.close(); pc?.dispose(); pc = null
        factory?.dispose(); factory = null
        sessionId = null
        scope.cancel()
        events.close()
        Log.i(TAG, "WebRTC disconnected")
    }

    // ── Initialisation interne ────────────────────────────────────────────────

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun buildPeerConnection() {
        pc = factory!!.createPeerConnection(rtcConfig, PcObserver())
            ?: error("PeerConnectionFactory.createPeerConnection returned null")
    }

    private fun addLocalAudioTrack() {
        // Contraintes audio : suppression d'écho + réduction de bruit + AGC
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("echoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("noiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("autoGainControl",  "true")
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
        }
        audioSource = factory!!.createAudioSource(constraints)
        audioTrack  = factory!!.createAudioTrack("elise_mic", audioSource!!)
        pc!!.addTrack(audioTrack!!, listOf("elise_stream"))
    }

    private fun createDataChannel() {
        val init = DataChannel.Init().apply { ordered = true }
        dc = pc!!.createDataChannel("control", init)
        dc!!.registerObserver(DcObserver())
    }

    // ── Helpers SDP (callbacks → coroutines) ─────────────────────────────────

    private suspend fun createOffer(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            pc!!.createOffer(
                sdpObserver(
                    onSuccess = { if (cont.isActive) cont.resume(it) },
                    onError   = { if (cont.isActive) cont.resumeWithException(Exception("createOffer: $it")) },
                ),
                MediaConstraints(),
            )
        }

    private suspend fun setLocalDescription(sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setLocalDescription(
                sdpObserver(
                    onSetOk = { if (cont.isActive) cont.resume(Unit) },
                    onError = { if (cont.isActive) cont.resumeWithException(Exception("setLocalDesc: $it")) },
                ),
                sdp,
            )
        }

    private suspend fun setRemoteDescription(sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setRemoteDescription(
                sdpObserver(
                    onSetOk = { if (cont.isActive) cont.resume(Unit) },
                    onError = { if (cont.isActive) cont.resumeWithException(Exception("setRemoteDesc: $it")) },
                ),
                sdp,
            )
        }

    private suspend fun postOffer(sdp: String, type: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("sdp", sdp)
                .put("type", type)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val resp = http.newCall(
                Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/rtc/offer?token=$token")
                    .post(body)
                    .build()
            ).execute()
            if (!resp.isSuccessful) error("POST /rtc/offer HTTP ${resp.code}")
            JSONObject(resp.body!!.string())
        }

    // ── PeerConnection Observer ───────────────────────────────────────────────

    private inner class PcObserver : PeerConnection.Observer {

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE gathering: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                if (!iceGatheringDone.isCompleted) iceGatheringDone.complete(Unit)
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "PeerConnection state: $state")
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // Remote audio track (TTS d'ÉLISE) — WebRTC SDK le route automatiquement
            // vers le haut-parleur/écouteur sans code supplémentaire
            Log.i(TAG, "Remote track received: ${receiver.track()?.kind()}")
        }

        // Implémentations obligatoires
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(a: Array<out IceCandidate>) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    // ── DataChannel Observer ──────────────────────────────────────────────────

    private inner class DcObserver : DataChannel.Observer {

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            try {
                val j = JSONObject(String(bytes))
                scope.launch {
                    events.send(
                        RtcEvent(
                            type       = j.optString("type"),
                            transcript = j.optString("transcript"),
                            sttMs      = j.optInt("stt_ms"),
                            totalMs    = j.optInt("total_ms"),
                        )
                    )
                }
            } catch (_: Exception) {
                Log.w(TAG, "DataChannel message parse error")
            }
        }

        override fun onStateChange() {
            Log.d(TAG, "DataChannel state: ${dc?.state()}")
        }

        override fun onBufferedAmountChange(amount: Long) {}
    }
}

// ── Factory SdpObserver ───────────────────────────────────────────────────────

private fun sdpObserver(
    onSuccess: ((SessionDescription) -> Unit)? = null,
    onSetOk:   (() -> Unit)?                   = null,
    onError:   ((String) -> Unit)?             = null,
): SdpObserver = object : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) { onSuccess?.invoke(sdp) }
    override fun onSetSuccess()                           { onSetOk?.invoke() }
    override fun onCreateFailure(error: String)           { onError?.invoke(error) }
    override fun onSetFailure(error: String)              { onError?.invoke(error) }
}
