package com.lnsgroup.elise.watch.audio

import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AudioPlayer"

class AudioPlayer(private val cacheDir: File) {

    private var mediaPlayer: MediaPlayer? = null
    private val tmpFile = File(cacheDir, "elise_response.mp3")

    // ── Streaming via pipe ────────────────────────────────────────────────────

    private var pipeWriteStream: OutputStream? = null
    private var streamingDeferred: CompletableDeferred<Unit>? = null

    /**
     * Opens a pipe and starts MediaPlayer reading from its read end.
     * Returns the write end — caller writes MP3 chunks, then closes it.
     * When the write end is closed the pipe EOF triggers onCompletion.
     * Must call [awaitStreamingPlayback] afterwards to wait for finish.
     */
    fun openStreamingPipe(): OutputStream {
        stop()

        val pipe = ParcelFileDescriptor.createPipe()
        val readFd  = pipe[0]
        val writeFd = pipe[1]
        val writeStream = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
        pipeWriteStream = writeStream

        val deferred = CompletableDeferred<Unit>()
        streamingDeferred = deferred

        val player = MediaPlayer()
        mediaPlayer = player

        player.setOnPreparedListener { mp ->
            Log.d(TAG, "Pipe playback ready, starting")
            mp.start()
        }
        player.setOnCompletionListener { mp ->
            Log.d(TAG, "Pipe playback complete")
            mp.release()
            mediaPlayer = null
            deferred.complete(Unit)
        }
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Pipe MediaPlayer error: what=$what extra=$extra")
            player.release()
            mediaPlayer = null
            deferred.completeExceptionally(Exception("MediaPlayer pipe error $what"))
            true
        }

        try {
            player.setDataSource(readFd.fileDescriptor)
            player.prepareAsync()
        } finally {
            // MediaPlayer dups the fd internally — safe to close our copy
            readFd.close()
        }

        return writeStream
    }

    /** Waits for the streaming pipe playback started by [openStreamingPipe] to finish. */
    suspend fun awaitStreamingPlayback() {
        streamingDeferred?.await()
        streamingDeferred = null
    }

    // ── Classic single-buffer playback (fallback) ─────────────────────────────

    /**
     * Plays a full MP3 buffer and waits for completion.
     * Used as fallback when pipe-based streaming is not applicable.
     */
    suspend fun playMp3(mp3Bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (mp3Bytes.isEmpty()) return@withContext

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
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
        try { pipeWriteStream?.close() } catch (_: Exception) {}
        pipeWriteStream = null
        streamingDeferred?.cancel()
        streamingDeferred = null
    }

    fun cleanup() {
        stop()
        tmpFile.delete()
    }
}
