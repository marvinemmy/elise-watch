package com.lnsgroup.elise.companion

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lnsgroup.elise.companion.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File

private const val TAG = "MainActivity"
private const val SAMPLE_RATE = 16000
private const val TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc19hZG1pbiI6dHJ1ZSwiZW1haWwiOiJtYXJ2aW5wa2VpdGFAZ21haWwuY29tIiwiZXhwIjoyMDk0NzA2MDYzLCJpYXQiOjE3NzkzNDYwNjN9.GXRa93bmRpNNaf7j165OW65sz6A-VSdsxX1J4t6imV8"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var recordJob: Job? = null

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else setStatus("Microphone requis")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        setStatus("Appuie sur le micro pour parler à Élise")

        binding.btnMic.setOnClickListener {
            if (isRecording) stopRecording() else checkMicAndRecord()
        }

        UpdateChecker.checkAsync(this) { status ->
            runOnUiThread {
                if (!status.startsWith("À jour")) setStatus(status)
            }
        }
    }

    private fun checkMicAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        isRecording = true
        binding.btnMic.text = "⏹"
        setStatus("J'écoute…")

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 5 * 2)

        recordJob = CoroutineScope(Dispatchers.IO).launch {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4
            )
            recorder.startRecording()
            val pcmBuf = mutableListOf<Byte>()
            val chunk = ShortArray(bufferSize / 2)
            val startMs = System.currentTimeMillis()

            // Enregistre max 10s ou jusqu'à stopRecording()
            while (isActive && isRecording && System.currentTimeMillis() - startMs < 10_000) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    for (i in 0 until n) {
                        pcmBuf.add((chunk[i].toInt() and 0xFF).toByte())
                        pcmBuf.add((chunk[i].toInt() shr 8).toByte())
                    }
                }
            }
            recorder.stop(); recorder.release()

            val pcm = pcmBuf.toByteArray()
            if (pcm.size < SAMPLE_RATE / 2) {
                withContext(Dispatchers.Main) {
                    isRecording = false; binding.btnMic.text = "🎙"
                    setStatus("Trop court — réessaie")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                isRecording = false; binding.btnMic.text = "🎙"
                setStatus("Traitement…")
            }

            try {
                val wav = EliseClient.pcmToWav(pcm)
                val response = EliseClient.sendVoice(wav, TOKEN)

                withContext(Dispatchers.Main) {
                    setStatus("Élise : ${response.responseText.take(80)}")
                }

                if (response.mp3Bytes.isNotEmpty()) {
                    playMp3(response.mp3Bytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur: ${e.message}")
                withContext(Dispatchers.Main) {
                    setStatus("Erreur : ${e.message?.take(60)}")
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordJob?.cancel()
        binding.btnMic.text = "🎙"
        setStatus("Traitement…")
    }

    private fun playMp3(mp3Bytes: ByteArray) {
        try {
            val tmp = File(cacheDir, "elise_response.mp3")
            tmp.writeBytes(mp3Bytes)
            val player = MediaPlayer()
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
        }
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }
}
