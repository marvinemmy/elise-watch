package com.lnsgroup.elise.watch.media

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

private const val TAG = "MusicController"

class MusicController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun playPause()  = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "play/pause")
    fun next()       = dispatch(KeyEvent.KEYCODE_MEDIA_NEXT,       "next")
    fun previous()   = dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS,   "previous")
    fun stop()       = dispatch(KeyEvent.KEYCODE_MEDIA_STOP,        "stop")
    fun volumeUp()   { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); Log.d(TAG, "volume up") }
    fun volumeDown() { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); Log.d(TAG, "volume down") }

    private fun dispatch(keyCode: Int, label: String) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
        Log.d(TAG, "media key dispatched: $label")
    }

    /**
     * Détecte et exécute une commande musicale depuis un transcript vocal.
     * Retourne true si une commande a été exécutée.
     */
    fun handleTranscript(transcript: String): Boolean {
        val t = transcript.lowercase()
        return when {
            PAUSE_KW.any  { it in t } -> { playPause();  true }
            NEXT_KW.any   { it in t } -> { next();       true }
            PREV_KW.any   { it in t } -> { previous();   true }
            VOL_UP_KW.any { it in t } -> { volumeUp();   true }
            VOL_DN_KW.any { it in t } -> { volumeDown(); true }
            else -> false
        }
    }

    companion object {
        private val PAUSE_KW  = listOf("pause la musique", "met en pause", "stop la musique", "arrête la musique", "stoppe la musique", "coupe la musique")
        private val NEXT_KW   = listOf("chanson suivante", "piste suivante", "musique suivante", "suivante", "next track", "skip")
        private val PREV_KW   = listOf("chanson précédente", "piste précédente", "musique précédente", "précédente", "previous track")
        private val VOL_UP_KW = listOf("monte le son", "plus fort", "augmente le volume", "volume plus", "hausse le son")
        private val VOL_DN_KW = listOf("baisse le son", "moins fort", "diminue le volume", "volume moins", "baisse le volume")
    }
}
