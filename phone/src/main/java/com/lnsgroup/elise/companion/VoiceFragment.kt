package com.lnsgroup.elise.companion

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lnsgroup.elise.companion.databinding.FragmentVoiceBinding
import kotlinx.coroutines.*
import java.io.File

private const val TAG = "VoiceFragment"

/**
 * Fragment voix — transport WebRTC (UDP/Opus) avec fallback WebSocket.
 *
 * Mode WebRTC (défaut) :
 *   - Tap pour démarrer la session → micro stream continu vers serveur
 *   - VAD côté serveur détecte fin d'énoncé → STT → LLM → TTS → audio retour
 *   - Tap pendant la réponse → interruption
 *   - Tap pendant l'écoute → ferme la session
 *
 * Mode WebSocket (fallback si WebRTC échoue) :
 *   - Push-to-talk classique : tap pour enregistrer, tap pour envoyer
 */
class VoiceFragment : Fragment() {

    private var _binding: FragmentVoiceBinding? = null
    private val binding get() = _binding!!
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── WebRTC ────────────────────────────────────────────────────────────────
    private var rtcClient : EliseRTCClient? = null
    private var rtcActive = false

    // ── WebSocket fallback ────────────────────────────────────────────────────
    private var isRecording = false
    private var wsActive    = false

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onMicGranted() else setStatus("Microphone requis")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentVoiceBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        binding.root.setOnClickListener { onMicTap() }
        binding.btnStop.setOnClickListener { stopEverything() }
        binding.waveView.setState(EliseWaveView.State.IDLE)
        setStatus("Appuie pour parler à Élise")

        UpdateChecker.checkAsync(requireContext()) { status ->
            if (!status.startsWith("À jour")) setStatus(status)
        }
        WatchOtaManager.checkAndPush(requireContext()) { status ->
            Log.d(TAG, "Watch OTA: $status")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }

    // ── Tap handler ───────────────────────────────────────────────────────────

    private fun onMicTap() {
        when {
            rtcActive                  -> handleRtcTap()
            wsActive || isRecording    -> stopRecording()
            else                       -> requestMicAndStart()
        }
    }

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) onMicGranted()
        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun onMicGranted() {
        scope.launch { startRtcSession() }
    }

    // ── WebRTC session ────────────────────────────────────────────────────────

    private fun handleRtcTap() {
        val client = rtcClient ?: return
        when {
            // Réponse en cours → interrompre
            binding.waveView.state == EliseWaveView.State.SPEAKING ||
            binding.waveView.state == EliseWaveView.State.PROCESSING -> {
                client.sendInterrupt()
                binding.waveView.setState(EliseWaveView.State.RECORDING)
                setStatus("À l'écoute… parle")
            }
            // En écoute → fermer la session
            else -> {
                scope.launch { closeRtcSession() }
            }
        }
    }

    private suspend fun startRtcSession() {
        binding.waveView.setState(EliseWaveView.State.PROCESSING)
        setStatus("Connexion WebRTC…")

        val client = EliseRTCClient(requireContext(), TOKEN)
        rtcClient  = client
        rtcActive  = true

        try {
            withContext(Dispatchers.IO) { client.connect() }

            binding.waveView.setState(EliseWaveView.State.RECORDING)
            setStatus("À l'écoute…  (appuie pour fermer)")

            // Boucle d'événements DataChannel
            for (evt in client.events) {
                when (evt.type) {
                    "processing"     -> {
                        binding.waveView.setState(EliseWaveView.State.PROCESSING)
                        setStatus("Élise réfléchit…")
                    }
                    "response_start" -> {
                        binding.waveView.setState(EliseWaveView.State.SPEAKING)
                        val preview = evt.transcript.take(100)
                            .let { if (evt.transcript.length > 100) "$it…" else it }
                        setStatus(preview)
                    }
                    "response_end"   -> {
                        binding.waveView.setState(EliseWaveView.State.RECORDING)
                        setStatus("À l'écoute…  (appuie pour fermer)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebRTC failed: ${e.message} — fallback WebSocket")
            closeRtcSession()
            // Fallback WebSocket push-to-talk
            startWebSocketFallback()
        }
    }

    private suspend fun closeRtcSession() {
        rtcActive = false
        rtcClient?.disconnect()
        rtcClient = null
        binding.waveView.setState(EliseWaveView.State.IDLE)
        setStatus("Appuie pour parler à Élise")
    }

    // ── WebSocket fallback (push-to-talk classique) ───────────────────────────

    private fun startWebSocketFallback() {
        if (wsActive || isRecording) return
        wsActive    = true
        isRecording = true
        binding.waveView.setState(EliseWaveView.State.RECORDING)
        setStatus("J'écoute…  (appuie pour arrêter)")

        val bufSize = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 5 * 2)

        scope.launch(Dispatchers.IO) {
            val recorder = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 4,
            )
            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) { setStatus("Microphone non disponible") }
                wsActive = false; return@launch
            }
            recorder.startRecording()
            val pcm   = mutableListOf<Byte>()
            val chunk = ShortArray(bufSize / 2)
            val start = System.currentTimeMillis()

            while (isActive && isRecording && System.currentTimeMillis() - start < 12_000) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val rms = kotlin.math.sqrt(chunk.take(n).map { it.toDouble() * it }.average()).toFloat()
                    withContext(Dispatchers.Main) { binding.waveView.setAmplitude(rms) }
                    for (i in 0 until n) {
                        pcm.add((chunk[i].toInt() and 0xFF).toByte())
                        pcm.add((chunk[i].toInt() shr 8).toByte())
                    }
                }
            }
            recorder.stop(); recorder.release()
            isRecording = false

            if (pcm.size < SAMPLE_RATE / 2) {
                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.IDLE)
                    setStatus("Trop court — appuie et parle")
                }
                wsActive = false; return@launch
            }

            withContext(Dispatchers.Main) {
                binding.waveView.setState(EliseWaveView.State.PROCESSING)
                setStatus("Élise réfléchit…")
            }

            try {
                val wav      = EliseClient.pcmToWav(pcm.toByteArray())
                val response = EliseClient.sendVoice(wav, TOKEN)

                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.SPEAKING)
                    val preview = response.responseText.take(100)
                        .let { if (response.responseText.length > 100) "$it…" else it }
                    setStatus(preview)
                }

                if (response.mp3Bytes.isNotEmpty()) playMp3(response.mp3Bytes)

                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.IDLE)
                    setStatus("Appuie pour reparler")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WS error: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.ERROR)
                    setStatus(e.message?.take(80) ?: "Erreur de connexion")
                    delay(2500)
                    binding.waveView.setState(EliseWaveView.State.IDLE)
                    setStatus("Appuie pour réessayer")
                }
            } finally {
                wsActive = false
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun stopEverything() {
        isRecording = false
        wsActive    = false
        scope.launch { closeRtcSession() }
        scope.cancel()
        EliseOverlayService.stop(requireContext())
        EliseCallMonitor.stop(requireContext())
        requireActivity().finish()
    }

    private fun playMp3(mp3: ByteArray) {
        try {
            val tmp = File(requireContext().cacheDir, "elise_resp.mp3")
            tmp.writeBytes(mp3)
            val player = MediaPlayer()
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Playback: ${e.message}")
        }
    }

    private fun setStatus(text: String) {
        _binding?.tvStatus?.text = text
    }
}
