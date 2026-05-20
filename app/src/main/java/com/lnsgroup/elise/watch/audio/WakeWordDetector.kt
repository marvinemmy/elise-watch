package com.lnsgroup.elise.watch.audio

import android.content.Context
import android.util.Log
import com.lnsgroup.elise.watch.Config
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs

private const val TAG = "WakeWordDetector"

class WakeWordDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val windowSamples = Config.SAMPLE_RATE  // 1 seconde à 16kHz
    private val ringBuffer = ShortArray(windowSamples)
    private var ringPos = 0
    private var lastDetectionMs = 0L

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val model = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(model, options)
            Log.i(TAG, "TFLite wake word model loaded")
        } catch (e: Exception) {
            Log.w(TAG, "TFLite model not found, using amplitude fallback: ${e.message}")
            interpreter = null
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(Config.WAKE_WORD_MODEL)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    /**
     * Ajoute des samples au ring buffer et vérifie si "Ok Élise" est détecté.
     * Retourne true si le wake word est confirmé.
     */
    fun process(samples: ShortArray): Boolean {
        // Cooldown : éviter double-déclenchement
        val now = System.currentTimeMillis()
        if (now - lastDetectionMs < Config.WAKE_WORD_COOLDOWN_MS) return false

        for (s in samples) {
            ringBuffer[ringPos % windowSamples] = s
            ringPos++
        }

        if (ringPos < windowSamples) return false  // pas assez de données

        val confidence = runInference()
        if (confidence >= Config.WAKE_WORD_THRESHOLD) {
            lastDetectionMs = now
            Log.i(TAG, "Wake word detected! confidence=${"%.2f".format(confidence)}")
            return true
        }
        return false
    }

    private fun runInference(): Float {
        val interp = interpreter ?: return amplitudeFallback()

        // Normaliser les samples : [-1, 1]
        val inputBuf = ByteBuffer.allocateDirect(windowSamples * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until windowSamples) {
            val idx = (ringPos - windowSamples + i + windowSamples) % windowSamples
            inputBuf.putFloat(ringBuffer[idx] / 32768f)
        }
        inputBuf.rewind()

        val output = Array(1) { FloatArray(1) }
        try {
            interp.run(inputBuf, output)
            return output[0][0]
        } catch (e: Exception) {
            Log.w(TAG, "Inference error: ${e.message}")
            return amplitudeFallback()
        }
    }

    /**
     * Fallback basé sur l'amplitude si le modèle TFLite n'est pas disponible.
     * Détecte une activité vocale forte — l'utilisateur doit appuyer le bouton
     * ou le modèle sera remplacé après entraînement.
     */
    private fun amplitudeFallback(): Float {
        val start = maxOf(0, ringPos - Config.SAMPLE_RATE / 2)
        var energy = 0.0
        for (i in start until ringPos) {
            val s = ringBuffer[i % windowSamples]
            energy += s.toDouble() * s.toDouble()
        }
        val rms = kotlin.math.sqrt(energy / (ringPos - start))
        return if (rms > 2000) 0.85f else 0f
    }

    fun reset() {
        ringPos = 0
        ringBuffer.fill(0)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
