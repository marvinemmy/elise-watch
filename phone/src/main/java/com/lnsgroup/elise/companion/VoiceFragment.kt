package com.lnsgroup.elise.companion

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
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

class VoiceFragment : Fragment() {

    private var _binding: FragmentVoiceBinding? = null
    private val binding get() = _binding!!
    private var isRecording = false
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else setStatus("Microphone requis")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentVoiceBinding.inflate(inflater, container, false)
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

    private fun onMicTap() {
        if (isRecording) stopRecording()
        else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startRecording()
            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        isRecording = true
        binding.waveView.setState(EliseWaveView.State.RECORDING)
        setStatus("J'écoute…  (appuie pour arrêter)")

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 5 * 2)

        scope.launch(Dispatchers.IO) {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 4
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) { setStatus("Microphone non disponible") }
                return@launch
            }
            recorder.startRecording()
            val pcm = mutableListOf<Byte>()
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
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.waveView.setState(EliseWaveView.State.PROCESSING)
                setStatus("Élise réfléchit…")
            }

            try {
                val wav = EliseClient.pcmToWav(pcm.toByteArray())
                val response = EliseClient.sendVoice(wav, TOKEN)

                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.SPEAKING)
                    val preview = if (response.responseText.length > 120)
                        response.responseText.take(117) + "…" else response.responseText
                    setStatus(preview)
                }

                if (response.mp3Bytes.isNotEmpty()) playMp3(response.mp3Bytes)

                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.IDLE)
                    setStatus("Appuie pour parler")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.ERROR)
                    setStatus(e.message?.take(80) ?: "Erreur de connexion")
                    delay(2500)
                    binding.waveView.setState(EliseWaveView.State.IDLE)
                    setStatus("Appuie pour réessayer")
                }
            }
        }
    }

    private fun stopRecording() { isRecording = false }

    private fun stopEverything() {
        isRecording = false
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

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
