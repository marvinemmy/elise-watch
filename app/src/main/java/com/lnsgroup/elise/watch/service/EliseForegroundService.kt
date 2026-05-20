package com.lnsgroup.elise.watch.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lnsgroup.elise.watch.Config
import com.lnsgroup.elise.watch.audio.AudioCapture
import com.lnsgroup.elise.watch.audio.AudioPlayer
import com.lnsgroup.elise.watch.audio.WakeWordDetector
import com.lnsgroup.elise.watch.network.EliseWebSocket
import com.lnsgroup.elise.watch.ui.EliseState
import kotlinx.coroutines.*

private const val TAG = "EliseForegroundService"
private const val NOTIF_CHANNEL_ID = "elise_listening"
private const val NOTIF_ID = 1

class EliseForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null
    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayer: AudioPlayer

    // État partagé avec l'UI via Intent broadcast
    companion object {
        const val ACTION_STATE_CHANGED = "com.lnsgroup.elise.watch.STATE_CHANGED"
        const val EXTRA_STATE = "state"

        fun start(context: Context) {
            val intent = Intent(context, EliseForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EliseForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeWordDetector = WakeWordDetector(this)
        audioCapture = AudioCapture()
        audioPlayer = AudioPlayer(cacheDir)
        Log.i(TAG, "Service créé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(EliseState.LISTENING))
        startListening()
        return START_STICKY  // redémarre automatiquement si tué
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        listeningJob?.cancel()
        audioCapture.stop()
        audioPlayer.cleanup()
        wakeWordDetector.close()
        scope.cancel()
        Log.i(TAG, "Service arrêté")
    }

    // ── Boucle d'écoute permanente ─────────────────────────────────────────────

    private fun startListening() {
        listeningJob?.cancel()
        listeningJob = scope.launch {
            broadcastState(EliseState.LISTENING)
            Log.i(TAG, "Écoute permanente démarrée")

            val rec = audioCapture.startContinuous()

            while (isActive) {
                val (samples, rms) = audioCapture.readChunk()
                if (samples.isEmpty()) continue

                val wakeDetected = wakeWordDetector.process(samples)
                if (!wakeDetected) continue

                // ── Wake word détecté ! ────────────────────────────────────────
                Log.i(TAG, "🎙️  Ok Élise détecté")
                vibrate(Config.VIB_WAKE)
                broadcastState(EliseState.RECORDING)
                updateNotification(EliseState.RECORDING)

                try {
                    // Enregistrer la phrase jusqu'au silence
                    audioCapture.stop()
                    val pcm = audioCapture.recordUntilSilence()

                    if (pcm.size < Config.SAMPLE_RATE / 2) {
                        Log.w(TAG, "Audio trop court, ignoré")
                        broadcastState(EliseState.LISTENING)
                    } else {
                        val wav = audioCapture.pcmToWav(pcm)
                        vibrate(Config.VIB_SEND)
                        broadcastState(EliseState.PROCESSING)
                        updateNotification(EliseState.PROCESSING)

                        processWithElise(wav)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur traitement: ${e.message}")
                    vibrate(Config.VIB_ERROR)
                    broadcastState(EliseState.ERROR)
                } finally {
                    // Reprendre l'écoute permanente
                    delay(500)
                    audioCapture.startContinuous()
                    wakeWordDetector.reset()
                    broadcastState(EliseState.LISTENING)
                    updateNotification(EliseState.LISTENING)
                }
            }
        }
    }

    private suspend fun processWithElise(wav: ByteArray) {
        val prefs = getSharedPreferences(Config.PREF_FILE, Context.MODE_PRIVATE)
        val token = prefs.getString(Config.KEY_TOKEN, null)
            ?: run {
                Log.e(TAG, "Pas de token — configure l'app d'abord")
                broadcastState(EliseState.NOT_CONFIGURED)
                return
            }

        val serverUrl = prefs.getString(Config.KEY_SERVER_URL, Config.WS_URL) ?: Config.WS_URL
        val ws = EliseWebSocket(serverUrl, token)

        try {
            val response = ws.sendVoice(wav)

            if (response.mp3Bytes.isNotEmpty()) {
                broadcastState(EliseState.SPEAKING, response.transcript)
                updateNotification(EliseState.SPEAKING)
                audioPlayer.playMp3(response.mp3Bytes)
                Log.i(TAG, "Réponse jouée (${response.responseMs}ms, transcript='${response.transcript}')")
            }
        } finally {
            ws.shutdown()
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "ÉLISE — Écoute active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ÉLISE écoute la phrase de déclenchement"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: EliseState, detail: String = ""): Notification {
        val icon = android.R.drawable.ic_btn_speak_now
        val text = when (state) {
            EliseState.LISTENING -> "En écoute — dites « Ok Élise »"
            EliseState.RECORDING -> "Je t'écoute..."
            EliseState.PROCESSING -> "Traitement..."
            EliseState.SPEAKING -> "Réponse en cours"
            EliseState.ERROR -> "Erreur — réessaie"
            EliseState.NOT_CONFIGURED -> "Configure le token dans l'app"
            EliseState.IDLE -> "En veille"
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("ÉLISE")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: EliseState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(state))
    }

    // ── Utils ──────────────────────────────────────────────────────────────────

    private fun vibrate(ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(ms)
        }
    }

    private fun broadcastState(state: EliseState, detail: String = "") {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, state.name)
            if (detail.isNotEmpty()) putExtra("detail", detail)
        })
    }
}
