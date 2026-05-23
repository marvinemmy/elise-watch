package com.lnsgroup.elise.watch.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.lnsgroup.elise.watch.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioCapture {

    private val cancelFlag = AtomicBoolean(false)

    fun cancelRecording() { cancelFlag.set(true) }

    private val bufferSize = AudioRecord.getMinBufferSize(
        Config.SAMPLE_RATE,
        Config.CHANNEL_CONFIG,
        Config.AUDIO_FORMAT
    ).coerceAtLeast(Config.SAMPLE_RATE / 10 * 2)  // min 100ms

    private var recorder: AudioRecord? = null
    private val shortBuffer = ShortArray(bufferSize / 2)

    /**
     * Starts continuous monitoring.
     * @param aec If true, uses VOICE_COMMUNICATION source (hardware AEC) + AcousticEchoCanceler
     *            so that the speaker output is subtracted from the mic — used during SPEAKING
     *            to avoid ÉLISE detecting her own voice as an interruption.
     */
    fun startContinuous(aec: Boolean = false): AudioRecord {
        val source = if (aec) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                     else MediaRecorder.AudioSource.MIC
        val rec = AudioRecord(
            source,
            Config.SAMPLE_RATE,
            Config.CHANNEL_CONFIG,
            Config.AUDIO_FORMAT,
            bufferSize * 4
        )
        // NS: always suppress background noise for cleaner VAD and wake word detection
        if (NoiseSuppressor.isAvailable()) {
            try { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
            catch (_: Exception) {}
        }
        if (aec) {
            // AEC: cancels speaker echo from mic signal (hardware-backed when available)
            if (AcousticEchoCanceler.isAvailable()) {
                try { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
                catch (_: Exception) {}
            }
        }
        rec.startRecording()
        recorder = rec
        return rec
    }

    fun stop() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }

    /**
     * Lit un buffer PCM16, retourne (samples, rmsAmplitude).
     */
    fun readChunk(): Pair<ShortArray, Float> {
        val rec = recorder ?: return Pair(ShortArray(0), 0f)
        val n = rec.read(shortBuffer, 0, shortBuffer.size)
        if (n <= 0) return Pair(ShortArray(0), 0f)
        val chunk = shortBuffer.copyOf(n)
        val rms = sqrt(chunk.map { it.toDouble() * it.toDouble() }.average()).toFloat()
        return Pair(chunk, rms)
    }

    /**
     * Enregistre jusqu'à silence ou MAX_RECORD_MS.
     * Retourne le buffer PCM16 complet.
     */
    suspend fun recordUntilSilence(): ByteArray = withContext(Dispatchers.IO) {
        cancelFlag.set(false)
        val out = ByteArrayOutputStream()

        // Attendre que le hardware micro soit libéré (stop() vient d'être appelé)
        var rec: AudioRecord? = null
        for (attempt in 1..3) {
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Config.SAMPLE_RATE,
                Config.CHANNEL_CONFIG,
                Config.AUDIO_FORMAT,
                bufferSize * 4
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                rec = candidate
                break
            }
            candidate.release()
            Log.w("AudioCapture", "AudioRecord not ready, attempt $attempt/3")
            Thread.sleep(50)
        }
        if (rec == null) throw IllegalStateException("AudioRecord failed to initialize after 3 attempts")
        if (NoiseSuppressor.isAvailable()) {
            try { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
            catch (_: Exception) {}
        }
        rec.startRecording()

        val chunkSize = Config.SAMPLE_RATE / 10  // 100ms chunks
        val buf = ShortArray(chunkSize)
        var silenceMs = 0L
        val startMs = System.currentTimeMillis()
        val byteOrder = ByteOrder.LITTLE_ENDIAN

        try {
            while (isActive) {
                if (cancelFlag.get()) break
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= Config.MAX_RECORD_MS) break

                val n = rec.read(buf, 0, chunkSize)
                if (n <= 0) continue

                // Écrire en little-endian dans le buffer de sortie
                val byteChunk = ByteBuffer.allocate(n * 2).order(byteOrder)
                for (i in 0 until n) byteChunk.putShort(buf[i])
                out.write(byteChunk.array())

                // Calcul RMS pour détection silence
                val rms = sqrt(buf.take(n).map { it.toDouble() * it.toDouble() }.average()).toFloat()
                Log.v("AudioCapture", "chunk rms=$rms silenceMs=$silenceMs")
                if (rms < Config.SILENCE_THRESHOLD_RMS) {
                    silenceMs += 100
                    if (silenceMs >= Config.SILENCE_DURATION_MS) break
                } else {
                    silenceMs = 0
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }

        out.toByteArray()
    }

    /**
     * Construit un WAV valide depuis du PCM16 brut.
     */
    fun pcmToWav(pcm: ByteArray): ByteArray {
        val totalDataLen = pcm.size + 36
        val bb = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(totalDataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)                       // PCM
        bb.putShort(1)                       // mono
        bb.putInt(Config.SAMPLE_RATE)
        bb.putInt(Config.SAMPLE_RATE * 2)    // byte rate
        bb.putShort(2)                       // block align
        bb.putShort(16)                      // bits per sample
        bb.put("data".toByteArray())
        bb.putInt(pcm.size)
        bb.put(pcm)
        return bb.array()
    }
}
