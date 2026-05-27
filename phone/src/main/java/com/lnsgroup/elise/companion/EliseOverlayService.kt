package com.lnsgroup.elise.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

private const val TAG = "EliseOverlay"
private const val CHANNEL_ID        = "elise_overlay"
private const val CHANNEL_EVENTS_ID = "elise_events"
private const val NOTIF_ID = 42
private const val ACTION_SET_CALL_MODE = "elise.SET_CALL_MODE"

class EliseOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var overlayView: EliseOverlayView
    private var textCard: View? = null
    private var tvMessage: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRecording = false
    private var callMode = false  // true = appel en cours, voix coupée, texte only

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = EliseOverlayView(this)
        overlayView.onTap = { toggleRecording() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        enableDrag(params)
        wm.addView(overlayView, params)

        // Écoute des notifications push serveur (gap fixes, alertes, etc.)
        startEventListener()

        // Long press sur l'orbe → ferme l'overlay
        overlayView.setOnLongClickListener {
            stop(this)
            true
        }

        // Carte texte pour les messages en mode appel
        setupTextCard()
    }

    private fun setupTextCard() {
        val card = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#CC010A10"))
            setPadding(24, 16, 24, 16)
            maxLines = 4
            visibility = View.GONE
        }
        val cardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
            width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        }
        wm.addView(card, cardParams)
        textCard = card
        tvMessage = card
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isCall = intent?.getBooleanExtra("call_mode", false) ?: false
        if (intent?.action == ACTION_SET_CALL_MODE) {
            setCallModeInternal(isCall)
        }
        return START_STICKY
    }

    private fun setCallModeInternal(active: Boolean) {
        callMode = active
        if (active) {
            // Appel en cours : orbe devient orange, carte texte visible
            overlayView.setState(EliseWaveView.State.PROCESSING)
            textCard?.visibility = View.VISIBLE
            tvMessage?.text = "ÉLISE — mode appel actif\nJe t'envoie les infos par écrit"
            // Cacher le message après 3s
            scope.launch {
                delay(3000)
                if (callMode) tvMessage?.text = ""
            }
            if (isRecording) isRecording = false  // couper l'enregistrement en cours
        } else {
            // Fin d'appel
            textCard?.visibility = View.GONE
            overlayView.setState(EliseWaveView.State.LISTENING)
        }
    }

    fun showTextMessage(text: String) {
        tvMessage?.text = text
        textCard?.visibility = View.VISIBLE
        scope.launch {
            delay(8000)
            textCard?.visibility = View.GONE
        }
    }

    private fun enableDrag(params: WindowManager.LayoutParams) {
        var initX = 0; var initY = 0; var initTX = 0; var initTY = 0
        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTX = ev.rawX.toInt(); initTY = ev.rawY.toInt()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - initTX
                    val dy = ev.rawY.toInt() - initTY
                    if (dx * dx + dy * dy > 100) {
                        params.x = initX - dx
                        params.y = initY + dy
                        wm.updateViewLayout(overlayView, params)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private var tapCount = 0
    private var lastTap = 0L

    private fun toggleRecording() {
        val now = System.currentTimeMillis()
        // Double tap = ouvrir panneau contrôle PC
        if (now - lastTap < 400) {
            tapCount++
            if (tapCount >= 2) {
                tapCount = 0
                showPcControlPanel()
                return
            }
        } else {
            tapCount = 1
        }
        lastTap = now

        if (callMode) {
            showTextMessage("Tu es en appel. Dis-moi ce dont tu as besoin par geste.")
            return
        }
        if (isRecording) {
            isRecording = false
        } else {
            startVoiceCapture()
        }
    }

    private fun showPcControlPanel() {
        scope.launch {
            val online = ElisePcControl.pcStatus()
            val statusText = if (online) "PC en ligne ✓" else "PC hors ligne"
            withContext(Dispatchers.Main) {
                tvMessage?.text = buildString {
                    appendLine("🖥  CONTRÔLE PC — $statusText")
                    appendLine()
                    appendLine("🦇 Double-tap → Bat Mode")
                    appendLine("📷 Triple-tap → Caméra")
                    appendLine("🎤 Tap×4 → Micro")
                }
                textCard?.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun startVoiceCapture() {
        isRecording = true
        overlayView.setState(EliseWaveView.State.RECORDING)

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 5 * 2)

        scope.launch(Dispatchers.IO) {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 4
            )
            recorder.startRecording()
            val pcm = mutableListOf<Byte>()
            val chunk = ShortArray(bufSize / 2)
            val start = System.currentTimeMillis()

            while (isActive && isRecording && System.currentTimeMillis() - start < 12_000) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val rms = kotlin.math.sqrt(chunk.take(n).map { it.toDouble() * it }.average()).toFloat()
                    withContext(Dispatchers.Main) { overlayView.setAmplitude(rms) }
                    for (i in 0 until n) {
                        pcm.add((chunk[i].toInt() and 0xFF).toByte())
                        pcm.add((chunk[i].toInt() shr 8).toByte())
                    }
                }
            }
            recorder.stop(); recorder.release()
            isRecording = false

            if (pcm.size < SAMPLE_RATE / 2) {
                withContext(Dispatchers.Main) { overlayView.setState(EliseWaveView.State.LISTENING) }
                return@launch
            }

            withContext(Dispatchers.Main) { overlayView.setState(EliseWaveView.State.PROCESSING) }

            try {
                val wav = EliseClient.pcmToWav(pcm.toByteArray())
                val response = EliseClient.sendVoice(wav, TOKEN)
                withContext(Dispatchers.Main) {
                    if (callMode) {
                        // En appel : afficher le texte, pas de son
                        val preview = if (response.responseText.length > 200)
                            response.responseText.take(197) + "…"
                        else response.responseText
                        showTextMessage(preview)
                    } else {
                        overlayView.setState(EliseWaveView.State.SPEAKING)
                        if (response.mp3Bytes.isNotEmpty()) playMp3(response.mp3Bytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                withContext(Dispatchers.Main) { overlayView.setState(EliseWaveView.State.ERROR) }
                delay(1500)
            }
            withContext(Dispatchers.Main) { overlayView.setState(EliseWaveView.State.LISTENING) }
        }
    }

    private fun playMp3(mp3: ByteArray) {
        try {
            val tmp = File(cacheDir, "elise_overlay.mp3")
            tmp.writeBytes(mp3)
            val player = MediaPlayer()
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Playback: ${e.message}")
        }
    }

    // ── Event listener — push notifications serveur → téléphone ─────────────

    private val eventClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)  // pas de timeout — connexion persistante
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun startEventListener() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val req = okhttp3.Request.Builder()
                        .url("${BuildConfig.API_BASE_URL}/ws/events?token=$TOKEN")
                        .build()
                    val latch = java.util.concurrent.CountDownLatch(1)
                    eventClient.newWebSocket(req, object : okhttp3.WebSocketListener() {
                        override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                            try {
                                val json = org.json.JSONObject(text)
                                if (json.optString("type") == "notification") {
                                    pushSystemNotification(
                                        json.optString("title", "Élise"),
                                        json.optString("body", ""),
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                        override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, r: okhttp3.Response?) {
                            Log.w(TAG, "Event WS failure: ${t.message}")
                            latch.countDown()
                        }
                        override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                            latch.countDown()
                        }
                    })
                    latch.await()
                } catch (e: Exception) {
                    Log.w(TAG, "Event listener error: ${e.message}")
                }
                delay(5_000)  // attendre avant de reconnecter
            }
        }
    }

    private fun pushSystemNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_EVENTS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
        Log.i(TAG, "Push notification shown: $title")
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Canal overlay (foreground service)
        val ch = NotificationChannel(CHANNEL_ID, "ÉLISE Active", NotificationManager.IMPORTANCE_LOW)
        ch.description = "ÉLISE overlay actif"
        nm.createNotificationChannel(ch)
        // Canal notifications push (haute priorité)
        val chEvents = NotificationChannel(
            CHANNEL_EVENTS_ID, "ÉLISE Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertes et nouvelles compétences acquises"
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(chEvents)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ÉLISE")
            .setContentText("Overlay actif — appuie pour parler")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { wm.removeView(overlayView) } catch (_: Exception) {}
        try { textCard?.let { wm.removeView(it) } } catch (_: Exception) {}
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EliseOverlayService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EliseOverlayService::class.java))
        }
        fun setCallMode(ctx: Context, active: Boolean) {
            ctx.startForegroundService(
                Intent(ctx, EliseOverlayService::class.java).apply {
                    action = ACTION_SET_CALL_MODE
                    putExtra("call_mode", active)
                }
            )
        }
    }
}
