package com.lnsgroup.elise.companion

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import kotlinx.coroutines.*

/**
 * Activité de reconnaissance et enrôlement facial.
 * Mode SCAN  : identification en temps réel via caméra avant.
 * Mode ENROLL: capture 8 photos pour enrôler une identité.
 */
class FaceScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var btnEnroll: Button
    private lateinit var btnScan: Button
    private lateinit var etName: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var scanner: EliseFaceScanner

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var enrollMode = false
    private val capturedBitmaps = mutableListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        scanner = EliseFaceScanner(this)
        startScanMode()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#010A10"))
            setPadding(32, 48, 32, 32)
        }

        // Titre
        root.addView(TextView(this).apply {
            text = "ÉLISE — Reconnaissance faciale"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            setPadding(0, 0, 0, 16)
        })

        // Prévisualisation caméra
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 480
            )
        }
        root.addView(previewView)

        // Résultat reconnaissance
        tvResult = TextView(this).apply {
            text = "Initialisation caméra…"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#4A8A9A"))
            setPadding(0, 12, 0, 12)
        }
        root.addView(tvResult)

        // Champ nom (pour enrôlement)
        etName = EditText(this).apply {
            hint = "Nom (pour enrôlement)"
            setHintTextColor(android.graphics.Color.parseColor("#1A3A4A"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#0A1A20"))
            setPadding(16, 12, 16, 12)
            visibility = View.GONE
        }
        root.addView(etName)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 8
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
        }
        root.addView(progressBar)

        // Boutons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        btnScan = Button(this).apply {
            text = "Scanner"
            setTextColor(android.graphics.Color.parseColor("#010A10"))
            setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 8 }
            setOnClickListener { startScanMode() }
        }
        btnRow.addView(btnScan)

        btnEnroll = Button(this).apply {
            text = "Enrôler"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A3344"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggleEnrollMode() }
        }
        btnRow.addView(btnEnroll)
        root.addView(btnRow)

        val scrollView = android.widget.ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun startScanMode() {
        enrollMode = false
        etName.visibility = View.GONE
        progressBar.visibility = View.GONE
        btnEnroll.text = "Enrôler"
        tvResult.text = "Scan en cours…"

        scanner.start(this, previewView) { results ->
            if (results.isEmpty()) {
                tvResult.text = "Aucun visage détecté"
                tvResult.setTextColor(android.graphics.Color.parseColor("#4A8A9A"))
            } else {
                val r = results.first()
                if (r.known) {
                    tvResult.text = "✓ ${r.name}  (${r.confidence.toInt()}%)"
                    tvResult.setTextColor(android.graphics.Color.parseColor("#00FF88"))
                } else {
                    tvResult.text = "Visage inconnu"
                    tvResult.setTextColor(android.graphics.Color.parseColor("#FF2255"))
                }
            }
        }
    }

    private fun toggleEnrollMode() {
        if (enrollMode) {
            // Valider l'enrôlement
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                tvResult.text = "Entre un nom d'abord"
                return
            }
            if (capturedBitmaps.size < 3) {
                tvResult.text = "Capture encore ${3 - capturedBitmaps.size} photo(s) — appuie sur 'Capturer'"
                return
            }
            tvResult.text = "Enrôlement de '$name' en cours…"
            btnEnroll.isEnabled = false
            scanner.enroll(name, capturedBitmaps.toList()) { msg ->
                tvResult.text = msg
                tvResult.setTextColor(
                    if (msg.contains("✓")) android.graphics.Color.parseColor("#00FF88")
                    else android.graphics.Color.parseColor("#FF9500")
                )
                btnEnroll.isEnabled = true
                capturedBitmaps.clear()
                progressBar.progress = 0
                startScanMode()
            }
        } else {
            // Démarrer le mode enrôlement
            enrollMode = true
            capturedBitmaps.clear()
            etName.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            btnEnroll.text = "Valider"
            tvResult.text = "Mode enrôlement — appuie sur 'Capturer' 8 fois"
            tvResult.setTextColor(android.graphics.Color.parseColor("#FF9500"))

            // Ajouter bouton capture
            addCaptureButton()
        }
    }

    private var captureBtn: Button? = null

    private fun addCaptureButton() {
        if (captureBtn != null) return
        // Trouver le root layout et ajouter le bouton
        val btn = Button(this).apply {
            text = "📸 Capturer (0/8)"
            setTextColor(android.graphics.Color.parseColor("#010A10"))
            setBackgroundColor(android.graphics.Color.parseColor("#FF9500"))
            setOnClickListener { captureCurrentFrame() }
        }
        // Ajouter via tag ou layout parent - approche simple via tag sur previewView
        captureBtn = btn
        (previewView.parent as? LinearLayout)?.addView(btn)
    }

    private fun captureCurrentFrame() {
        previewView.bitmap?.let { bmp ->
            if (capturedBitmaps.size < 8) {
                capturedBitmaps.add(bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false))
                progressBar.progress = capturedBitmaps.size
                captureBtn?.text = "📸 Capturer (${capturedBitmaps.size}/8)"
                tvResult.text = "${capturedBitmaps.size}/8 photos capturées"
                if (capturedBitmaps.size >= 8) {
                    captureBtn?.isEnabled = false
                    tvResult.text = "8 photos capturées — appuie sur 'Valider'"
                    tvResult.setTextColor(android.graphics.Color.parseColor("#00FF88"))
                }
            }
        } ?: run {
            tvResult.text = "Impossible de capturer — assure-toi que la caméra est active"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        scope.cancel()
    }
}
