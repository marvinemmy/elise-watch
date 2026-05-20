package com.lnsgroup.elise.watch.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AudioPlayer"

class AudioPlayer(private val cacheDir: File) {

    private var mediaPlayer: MediaPlayer? = null
    private val tmpFile = File(cacheDir, "elise_response.mp3")

    /**
     * Joue un buffer MP3 et attend la fin de la lecture.
     */
    suspend fun playMp3(mp3Bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (mp3Bytes.isEmpty()) return@withContext

        // Écrire dans un fichier temporaire (MediaPlayer ne lit pas directement depuis ByteArray)
        FileOutputStream(tmpFile).use { it.write(mp3Bytes) }

        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            mediaPlayer = player

            player.setOnCompletionListener {
                Log.d(TAG, "Playback complete")
                it.release()
                mediaPlayer = null
                if (cont.isActive) cont.resume(Unit)
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                player.release()
                mediaPlayer = null
                if (cont.isActive) cont.resumeWithException(Exception("MediaPlayer error $what"))
                true
            }

            try {
                player.setDataSource(tmpFile.absolutePath)
                player.prepare()
                player.start()
                Log.d(TAG, "Playing ${mp3Bytes.size / 1024}KB MP3")
            } catch (e: Exception) {
                player.release()
                mediaPlayer = null
                if (cont.isActive) cont.resumeWithException(e)
            }

            cont.invokeOnCancellation {
                player.stop()
                player.release()
                mediaPlayer = null
            }
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
    }

    fun cleanup() {
        stop()
        tmpFile.delete()
    }
}
