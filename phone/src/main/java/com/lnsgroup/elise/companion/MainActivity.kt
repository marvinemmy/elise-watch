package com.lnsgroup.elise.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lnsgroup.elise.companion.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permManager: ElisePermissionManager
    private var isRecording = false
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else setStatus("Microphone requis pour parler à Élise")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        binding.root.setOnClickListener { onMicTap() }

        binding.btnStop.setOnClickListener { stopEverything() }

        // Gestionnaire de permissions centralisé
        permManager = ElisePermissionManager(this)
        permManager.register()
        permManager.requestAll {
            startEliseServices()
        }

        setStatus("Appuie pour parler à Élise")

        UpdateChecker.checkAsync(this) { status ->
            if (!status.startsWith("À jour")) setStatus(status)
        }
    }

    private fun startEliseServices() {
        if (Settings.canDrawOverlays(this)) {
            EliseOverlayService.start(this)
        }
        if (permManager.hasPhone()) {
            EliseCallMonitor.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-vérifie au retour (l'utilisateur vient peut-être d'accorder l'overlay)
        if (Settings.canDrawOverlays(this)) {
            EliseOverlayService.start(this)
        }
    }

    private fun onMicTap() {
        if (isRecording) stopRecording()
        else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
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
            recorder.startRecording()
            val pcm = mutableListOf<Byte>()
            val chunk = ShortArray(bufSize / 2)
            val start = System.currentTimeMillis()

            while (isActive && isRecording && System.currentTimeMillis() - start < 12_000) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val rms = kotlin.math.sqrt(chunk.take(n)
                        .map { it.toDouble() * it }.average()).toFloat()
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
                    binding.waveView.setState(EliseWaveView.State.LISTENING)
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
                    val preview = if (response.responseText.length > 100)
                        response.responseText.take(97) + "…"
                    else response.responseText
                    setStatus(preview)
                }

                if (response.mp3Bytes.isNotEmpty()) {
                    playMp3(response.mp3Bytes)
                }

                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.LISTENING)
                    setStatus("Appuie pour parler à Élise")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.waveView.setState(EliseWaveView.State.ERROR)
                    setStatus("Connexion impossible — vérifie internet")
                    delay(2000)
                    binding.waveView.setState(EliseWaveView.State.LISTENING)
                    setStatus("Appuie pour réessayer")
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
    }

    private fun stopEverything() {
        isRecording = false
        scope.cancel()
        EliseOverlayService.stop(this)
        EliseCallMonitor.stop(this)
        finish()
    }

    private fun playMp3(mp3: ByteArray) {
        try {
            val tmp = File(cacheDir, "elise_resp.mp3")
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
        binding.tvStatus.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
