package com.lnsgroup.elise.watch.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AudioPlayer"

class AudioPlayer(private val cacheDir: File) {

    private var mediaPlayer: MediaPlayer? = null
    private val tmpFile = File(cacheDir, "elise_response.mp3")

    // Holds the active continuation so stop() can unblock playMp3()
    private val activeCont = AtomicReference<CancellableContinuation<Unit>?>(null)

    /**
     * Plays a full MP3 buffer and waits for completion.
     * Returns immediately if stop() is called mid-playback (no exception thrown).
     */
    suspend fun playMp3(mp3Bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (mp3Bytes.isEmpty()) return@withContext

        FileOutputStream(tmpFile).use { it.write(mp3Bytes) }

        suspendCancellableCoroutine { cont ->
            activeCont.set(cont)

            val player = MediaPlayer()
            mediaPlayer = player

            player.setOnCompletionListener {
                it.release()
                mediaPlayer = null
                val c = activeCont.getAndSet(null)
                if (c?.isActive == true) c.resume(Unit)
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                player.release()
                mediaPlayer = null
                val c = activeCont.getAndSet(null)
                if (c?.isActive == true) c.resumeWithException(Exception("MediaPlayer error $what"))
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
                val c = activeCont.getAndSet(null)
                if (c?.isActive == true) c.resumeWithException(e)
            }

            cont.invokeOnCancellation {
                mediaPlayer?.let { mp -> try { mp.stop() } catch (_: Exception) {}; mp.release() }
                mediaPlayer = null
                activeCont.set(null)
            }
        }
    }

    /**
     * Stops playback immediately and unblocks any suspended playMp3() call.
     * The playMp3() coroutine resumes normally (no exception).
     */
    fun stop() {
        mediaPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
        // Resume the suspended playMp3() so its caller can check the interrupted flag
        val cont = activeCont.getAndSet(null)
        if (cont?.isActive == true) cont.resume(Unit)
    }

    fun cleanup() {
        stop()
        tmpFile.delete()
    }
}
