package com.lnsgroup.elise.watch.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lnsgroup.elise.watch.Config
import com.lnsgroup.elise.watch.audio.AudioCapture
import com.lnsgroup.elise.watch.audio.AudioPlayer
import com.lnsgroup.elise.watch.health.HealthDataCollector
import com.lnsgroup.elise.watch.media.MusicController
import com.lnsgroup.elise.watch.network.EliseWebSocket
import com.lnsgroup.elise.watch.ui.EliseState
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "EliseForegroundService"
private const val NOTIF_CHANNEL_ID        = "elise_listening"
private const val NOTIF_CHANNEL_EVENTS_ID = "elise_events"
private const val NOTIF_ID = 1

class EliseForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var healthCollector: HealthDataCollector
    private lateinit var musicController: MusicController

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

    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var lastAlertedPct = 100

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (scale <= 0) return
            val pct = level * 100 / scale

            val threshold = when {
                pct <= 15 && lastAlertedPct > 15 -> { lastAlertedPct = 15; 15 }
                pct <= 25 && lastAlertedPct > 25 -> { lastAlertedPct = 25; 25 }
                else -> -1
            }
            if (threshold > 0) {
                val msg = if (threshold == 15)
                    "Attention, batterie critique à $threshold pourcent."
                else
                    "Batterie faible, $threshold pourcent restants."
                tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "battery_$threshold")
                Log.i(TAG, "Battery alert: $pct%")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioCapture    = AudioCapture()
        audioPlayer     = AudioPlayer(cacheDir)
        healthCollector = HealthDataCollector(this)
        musicController  = MusicController(this)
        healthCollector.start()

        // Keep CPU running in battery saver mode
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "elise:foreground").apply {
            setReferenceCounted(false)
            acquire()
        }

        // TTS for battery alerts
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.FRENCH
            }
        }

        // Battery level monitoring
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Push notifications depuis le serveur (gap fixes, alertes)
        startEventListener()
    }

    // ── Event listener — push notifications serveur → montre ──────────────────

    private val eventHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun startEventListener() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val token = Config.PRELOADED_TOKEN
                    val url   = "${Config.API_BASE_URL.replace("https://", "wss://")}/ws/events?token=$token"
                    val req   = okhttp3.Request.Builder().url(url).build()
                    val latch = java.util.concurrent.CountDownLatch(1)
                    eventHttpClient.newWebSocket(req, object : okhttp3.WebSocketListener() {
                        override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                            try {
                                val json = org.json.JSONObject(text)
                                if (json.optString("type") == "notification") {
                                    showWatchNotification(
                                        json.optString("title", "Élise"),
                                        json.optString("body", ""),
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                        override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, r: okhttp3.Response?) {
                            latch.countDown()
                        }
                        override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                            latch.countDown()
                        }
                    })
                    latch.await()
                } catch (e: Exception) {
                    Log.w(TAG, "Event WS error: ${e.message}")
                }
                delay(5_000)
            }
        }
    }

    private fun showWatchNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_EVENTS_ID)
            .setSmallIcon(com.lnsgroup.elise.watch.R.drawable.ic_elise)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
        // Annoncer vocalement si important
        if (title.contains("compétence", ignoreCase = true) ||
            title.contains("capacité", ignoreCase = true)) {
            tts?.speak(
                "Nouvelle compétence acquise : $body".take(120),
                android.speech.tts.TextToSpeech.QUEUE_ADD, null, "gap_fix"
            )
        }
        Log.i(TAG, "Watch notification: $title")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACTIVATE) {
            Log.d(TAG, "ACTION_ACTIVATE recu")
            if (isSpeaking.get()) audioPlayer.stop()
            else activateTrigger.set(true)
            return START_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(EliseState.WAITING))
        WatchRoutineScheduler.scheduleAll(this)
        startMainLoop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        listeningJob?.cancel()
        audioCapture.stop()
        audioPlayer.cleanup()
        healthCollector.stop()
        scope.cancel()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        tts?.shutdown(); tts = null
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ── Boucle principale ──────────────────────────────────────────────────────

    private fun startMainLoop() {
        listeningJob?.cancel()
        listeningJob = scope.launch {
            broadcastState(EliseState.WAITING)
            var isListening    = false
            var lastActivityMs = System.currentTimeMillis()
            var vadMs          = 0L
            val chunkMs        = (Config.SAMPLE_RATE.toFloat() / 10 / Config.SAMPLE_RATE * 1000).toLong()
                                     .coerceAtLeast(80L)

            // Mic off at start — only turns on when user double taps

            while (isActive) {

                // ── WAITING : mic éteint, attend double tap uniquement ────────
                if (!isListening) {
                    if (activateTrigger.getAndSet(false)) {
                        audioCapture.startContinuous()
                        isListening    = true
                        lastActivityMs = System.currentTimeMillis()
                        vadMs          = 0L
                        vibrateOnce(60)
                        broadcastState(EliseState.LISTENING)
                        updateNotification(EliseState.LISTENING)
                        continue
                    }
                    // Mic éteint — polling léger, pas de wake word, économie batterie
                    delay(200)
                    continue
                }

                // ── LISTENING : mic actif ──────────────────────────────────────
                val (_, rms) = audioCapture.readChunk()

                // Double tap pendant LISTENING → annuler, mic éteint
                if (activateTrigger.getAndSet(false)) {
                    audioCapture.stop()
                    isListening = false
                    vadMs       = 0L
                    broadcastState(EliseState.WAITING)
                    updateNotification(EliseState.WAITING)
                    continue
                }

                // Silence prolongé → retour WAITING, mic éteint
                if (rms >= Config.SILENCE_THRESHOLD_RMS) lastActivityMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - lastActivityMs >= Config.SILENCE_TO_WAIT_MS) {
                    audioCapture.stop()
                    isListening = false
                    vadMs       = 0L
                    broadcastState(EliseState.WAITING)
                    updateNotification(EliseState.WAITING)
                    continue
                }

                // ── VAD : détection parole → RECORDING ────────────────────────
                if (rms >= Config.VAD_THRESHOLD_RMS) vadMs += chunkMs else vadMs = 0L
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
        // Single short pulse to confirm processing started — no repetition
        vibrateOnce(Config.VIB_PROCESSING_PULSE)
        vibJob = null
    }

    private fun stopProcessingVibration() { vibJob?.cancel(); vibJob = null }

    // ── Traitement ÉLISE ───────────────────────────────────────────────────────

    private suspend fun processWithElise(pcm: ByteArray) {
        val wav   = audioCapture.pcmToWav(pcm)
        val prefs = getSharedPreferences(Config.PREF_FILE, Context.MODE_PRIVATE)
        val token = prefs.getString(Config.KEY_TOKEN, null)
            ?: run { broadcastState(EliseState.NOT_CONFIGURED); return }
        val serverUrl = prefs.getString(Config.KEY_SERVER_URL, Config.WS_URL) ?: Config.WS_URL

        // Lecture des capteurs biologiques et géolocalisation
        val hrBpm = healthCollector.lastHr.takeIf { it > 0 }
        val location = com.lnsgroup.elise.watch.health.WatchLocation.getLastKnown(this)

        val response = try {
            if (com.lnsgroup.elise.watch.network.EliseConnectionHelper.hasDirectInternet()) {
                val ws = EliseWebSocket(serverUrl, token)
                try {
                    val wavWithContext = enrichWavWithContext(wav)
                    ws.sendVoice(wavWithContext, heartRate = hrBpm,
                        lat = location?.first, lon = location?.second)
                } finally { ws.shutdown() }
            } else {
                Log.i(TAG, "No direct internet, routing via phone proxy")
                com.lnsgroup.elise.watch.network.EliseConnectionHelper.sendViaPhoneProxy(
                    this, wav, token
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "processWithElise error: ${e.message}")
            throw e
        }

        // Détecter et exécuter commandes musicales depuis le transcript utilisateur
        // (exécuté en parallèle — la réponse LLM confirme verbalement l'action)
        val transcript = response.transcript.trim()
        if (musicController.handleTranscript(transcript)) {
            Log.i(TAG, "Music command executed from: $transcript")
        }

        val normalised = transcript.lowercase().trimEnd('.', '!', '?', ' ')
        if (normalised in Config.STOP_WORDS) {
            Log.i(TAG, "Off word: '$normalised'")
            return
        }
        if (response.mp3Bytes.isEmpty()) return

        broadcastState(EliseState.SPEAKING, transcript)
        updateNotification(EliseState.SPEAKING)
        isSpeaking.set(true)

        val interrupted = AtomicBoolean(false)

        // Play MP3 + simultaneous VAD monitoring for real-time interruption
        supervisorScope {
            val monitorJob = launch(Dispatchers.IO) {
                audioCapture.startContinuous(aec = true)
                var vadMs = 0L
                var warmupMs = 0L
                try {
                    while (isActive) {
                        val (_, rms) = audioCapture.readChunk()
                        warmupMs += 100
                        if (warmupMs < 500L) continue

                        // Voix détectée → stoppe l'audio immédiatement.
                        // Le transcript est ensuite vérifié côté serveur :
                        // "Élise off" → retour silencieux, toute autre phrase → reformulation.
                        if (rms >= Config.VAD_INTERRUPT_RMS) {
                            vadMs += 100
                            if (vadMs >= Config.VAD_INTERRUPT_MS) {
                                Log.i(TAG, "Interruption vocale (rms=$rms)")
                                interrupted.set(true)
                                audioPlayer.stop()
                                vibrateOnce(40)
                                break
                            }
                        } else {
                            vadMs = 0
                        }
                    }
                } finally {
                    if (!interrupted.get()) audioCapture.stop()
                }
            }

            val playJob = launch {
                try {
                    audioPlayer.playMp3(response.mp3Bytes)
                } finally {
                    monitorJob.cancel()
                    isSpeaking.set(false)
                }
            }
            playJob.join()
        }

        // ── Interruption : enregistrer la correction et relancer ÉLISE ──────────
        if (interrupted.get()) {
            broadcastState(EliseState.RECORDING)
            updateNotification(EliseState.RECORDING)

            try {
                audioCapture.stop()
                val correctionPcm = audioCapture.recordUntilSilence()

                if (correctionPcm.size >= Config.SAMPLE_RATE / 2) {
                    broadcastState(EliseState.PROCESSING)
                    updateNotification(EliseState.PROCESSING)
                    startProcessingVibration()
                    processWithElise(correctionPcm)
                } else {
                    broadcastState(EliseState.LISTENING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur après interruption: ${e.message}")
                broadcastState(EliseState.ERROR)
                delay(1500)
            } finally {
                stopProcessingVibration()
            }
        }
    }

    /**
     * Si le transcript semble être une demande de notifications, injecte
     * le contexte des notifications récentes dans le WAV via un fichier WAV
     * enrichi — en réalité, l'enrichissement se fait côté URL ou dans le transcript
     * côté serveur. Ici on retourne le wav original, l'enrichissement se fait
     * via l'URL WebSocket (query params hr) et via le transcript côté serveur.
     */
    private fun enrichWavWithContext(wav: ByteArray): ByteArray = wav

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_ID, "ÉLISE", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_EVENTS_ID, "ÉLISE Alertes", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Nouvelles compétences et alertes système"
                    enableVibration(true)
                }
        )
    }

    private fun buildNotification(state: EliseState): Notification {
        val text = when (state) {
            EliseState.WAITING    -> "Veille — double tap pour parler"
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
