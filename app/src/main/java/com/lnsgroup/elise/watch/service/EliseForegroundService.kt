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
import com.lnsgroup.elise.watch.network.EliseWebSocket
import com.lnsgroup.elise.watch.ui.EliseState
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "EliseForegroundService"
private const val NOTIF_CHANNEL_ID = "elise_listening"
private const val NOTIF_ID = 1

class EliseForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayer: AudioPlayer

    companion object {
        const val ACTION_STATE_CHANGED = "com.lnsgroup.elise.watch.STATE_CHANGED"
        const val ACTION_ACTIVATE      = "com.lnsgroup.elise.watch.ACTIVATE"
        const val EXTRA_STATE          = "state"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EliseForegroundService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, EliseForegroundService::class.java))
        }
    }

    private val activateTrigger = AtomicBoolean(false)
    private val isSpeaking      = AtomicBoolean(false)

    @Volatile private var currentState = EliseState.WAITING

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioCapture = AudioCapture()
        audioPlayer  = AudioPlayer(cacheDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACTIVATE) {
            Log.d(TAG, "ACTION_ACTIVATE recu")
            if (isSpeaking.get()) audioPlayer.stop()
            else activateTrigger.set(true)
            return START_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(EliseState.WAITING))
        startMainLoop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        listeningJob?.cancel()
        audioCapture.stop()
        audioPlayer.cleanup()
        scope.cancel()
    }

    // ── Boucle principale ──────────────────────────────────────────────────────

    private fun startMainLoop() {
        listeningJob?.cancel()
        listeningJob = scope.launch {
            broadcastState(EliseState.WAITING)
            var isListening    = false
            var lastActivityMs = System.currentTimeMillis()
            var vadMs          = 0L   // durée parole continue détectée
            val chunkMs        = (Config.SAMPLE_RATE.toFloat() / 10 / Config.SAMPLE_RATE * 1000).toLong()
                                     .coerceAtLeast(80L)  // ~100ms par chunk

            audioCapture.startContinuous()

            while (isActive) {
                val (samples, rms) = audioCapture.readChunk()
                if (samples.isEmpty()) { delay(10); continue }

                // ── Double tap ─────────────────────────────────────────────────
                if (activateTrigger.getAndSet(false)) {
                    if (!isListening) {
                        isListening    = true
                        lastActivityMs = System.currentTimeMillis()
                        vadMs          = 0L
                        broadcastState(EliseState.LISTENING)
                        updateNotification(EliseState.LISTENING)
                    } else {
                        // Déjà en écoute → déclenche immédiatement
                        vadMs = Config.VAD_TRIGGER_MS + 1
                    }
                }

                if (!isListening) continue

                // ── Silence → WAITING après 3s ────────────────────────────────
                if (rms >= Config.SILENCE_THRESHOLD_RMS) lastActivityMs = System.currentTimeMillis()
                val silenced = System.currentTimeMillis() - lastActivityMs >= Config.SILENCE_TO_WAIT_MS
                if (silenced && currentState == EliseState.LISTENING) {
                    isListening = false
                    vadMs       = 0L
                    broadcastState(EliseState.WAITING)
                    updateNotification(EliseState.WAITING)
                    continue
                }

                // ── VAD : détection parole → RECORDING ────────────────────────
                if (rms >= Config.VAD_THRESHOLD_RMS) {
                    vadMs += chunkMs
                } else {
                    vadMs = 0L
                }
                if (vadMs < Config.VAD_TRIGGER_MS) continue

                vadMs = 0L
                lastActivityMs = System.currentTimeMillis()
                broadcastState(EliseState.RECORDING)
                updateNotification(EliseState.RECORDING)

                try {
                    audioCapture.stop()

                    // Double tap pendant enregistrement = annuler
                    val cancelJob = launch {
                        while (isActive) {
                            if (activateTrigger.getAndSet(false)) {
                                audioCapture.cancelRecording()
                                break
                            }
                            delay(80)
                        }
                    }
                    val pcm = audioCapture.recordUntilSilence()
                    cancelJob.cancel()

                    if (pcm.size < Config.SAMPLE_RATE / 2) {
                        broadcastState(EliseState.LISTENING)
                    } else {
                        broadcastState(EliseState.PROCESSING)
                        updateNotification(EliseState.PROCESSING)
                        startProcessingVibration()
                        processWithElise(pcm)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur: ${e.message}")
                    broadcastState(EliseState.ERROR)
                    delay(1500)
                } finally {
                    stopProcessingVibration()
                    delay(400)
                    audioCapture.startContinuous()
                    lastActivityMs = System.currentTimeMillis()
                    vadMs          = 0L
                    isListening    = true
                    broadcastState(EliseState.LISTENING)
                    updateNotification(EliseState.LISTENING)
                }
            }
        }
    }

    // ── Vibration pulsée PROCESSING uniquement ─────────────────────────────────

    private var vibJob: Job? = null

    private fun startProcessingVibration() {
        vibJob?.cancel()
        vibJob = scope.launch {
            while (isActive) {
                vibrateOnce(Config.VIB_PROCESSING_PULSE)
                delay(Config.VIB_PROCESSING_INTERVAL)
            }
        }
    }

    private fun stopProcessingVibration() { vibJob?.cancel(); vibJob = null }

    // ── Traitement ÉLISE ───────────────────────────────────────────────────────

    private suspend fun processWithElise(pcm: ByteArray) {
        val wav   = audioCapture.pcmToWav(pcm)
        val prefs = getSharedPreferences(Config.PREF_FILE, Context.MODE_PRIVATE)
        val token = prefs.getString(Config.KEY_TOKEN, null)
            ?: run { broadcastState(EliseState.NOT_CONFIGURED); return }
        val serverUrl = prefs.getString(Config.KEY_SERVER_URL, Config.WS_URL) ?: Config.WS_URL

        val response = try {
            if (com.lnsgroup.elise.watch.network.EliseConnectionHelper.hasDirectInternet()) {
                // Chemin direct — WebSocket vers lnsgroup.dev
                val ws = EliseWebSocket(serverUrl, token)
                try { ws.sendVoice(wav) } finally { ws.shutdown() }
            } else {
                // Fallback Bluetooth — proxy via téléphone
                Log.i(TAG, "No direct internet, routing via phone proxy")
                broadcastState(EliseState.PROCESSING)
                com.lnsgroup.elise.watch.network.EliseConnectionHelper.sendViaPhoneProxy(
                    this, wav, token
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "processWithElise error: ${e.message}")
            throw e  // remonté au caller qui gère ERROR + delay
        }

        val normalised = response.transcript.trim().lowercase().trimEnd('.', '!', '?', ' ')
        if (normalised in Config.STOP_WORDS) {
            Log.i(TAG, "Off word: '$normalised'")
            return
        }

        if (response.mp3Bytes.isNotEmpty()) {
            broadcastState(EliseState.SPEAKING, response.transcript)
            updateNotification(EliseState.SPEAKING)
            isSpeaking.set(true)
            try {
                audioPlayer.playMp3(response.mp3Bytes)
            } finally {
                isSpeaking.set(false)
            }
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "ÉLISE", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: EliseState): Notification {
        val text = when (state) {
            EliseState.WAITING    -> "Veille — double tap pour activer"
            EliseState.LISTENING  -> "Écoute active — parle pour déclencher"
            EliseState.RECORDING  -> "Enregistrement..."
            EliseState.PROCESSING -> "Traitement..."
            EliseState.SPEAKING   -> "Réponse en cours"
            EliseState.ERROR      -> "Erreur"
            else                  -> "ÉLISE"
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(com.lnsgroup.elise.watch.R.drawable.ic_elise)
            .setContentTitle("ÉLISE")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: EliseState) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(state))
    }

    private fun vibrateOnce(ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator?.vibrate(ms)
    }

    private fun broadcastState(state: EliseState, detail: String = "") {
        currentState = state
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state.name)
            if (detail.isNotEmpty()) putExtra("detail", detail)
        })
    }
}
