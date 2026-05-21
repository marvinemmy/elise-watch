package com.lnsgroup.elise.watch.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/**
 * Rendu temps-réel : orbe central + ondes de particules 3D façon magnific.
 * Pure Canvas Android — aucune dépendance Compose.
 */
class EliseParticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Couleurs par état ──────────────────────────────────────────────────────
    private val palette = mapOf(
        EliseState.LISTENING    to intArrayOf(0xFF00E5FF.toInt(), 0xFF0080FF.toInt(), 0xFF00BFFF.toInt()),
        EliseState.RECORDING    to intArrayOf(0xFF00FF88.toInt(), 0xFF00E5A0.toInt(), 0xFF80FFD0.toInt()),
        EliseState.PROCESSING   to intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFFFF6600.toInt()),
        EliseState.SPEAKING     to intArrayOf(0xFFFF9500.toInt(), 0xFFFFD700.toInt(), 0xFFFF4500.toInt()),
        EliseState.ERROR        to intArrayOf(0xFFFF2255.toInt(), 0xFFFF0080.toInt(), 0xFFCC0044.toInt()),
        EliseState.IDLE         to intArrayOf(0xFF1A3344.toInt(), 0xFF0D2233.toInt(), 0xFF243344.toInt()),
        EliseState.NOT_CONFIGURED to intArrayOf(0xFFFF2255.toInt(), 0xFF880022.toInt(), 0xFFFF0040.toInt()),
    )

    // ── Particules ────────────────────────────────────────────────────────────
    private data class Particle(
        var angle: Float,        // angle orbital (radians)
        var radius: Float,       // distance au centre
        var z: Float,            // profondeur [-1, 1] pour effet 3D
        var speed: Float,        // vitesse angulaire
        var size: Float,         // taille de base
        var waveOffset: Float,   // décalage phase onde
        var colorIdx: Int,       // index dans la palette courante
        var alpha: Float = 1f,   // opacité
        var trailLen: Int = 4,   // longueur de traîne
    )

    private val particles = mutableListOf<Particle>()
    private val N_PARTICLES = 90
    private val N_CORE = 18     // particules orbitales rapprochées du noyau

    // ── Peintures ─────────────────────────────────────────────────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#010A10") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.25f
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A7A99")
        textAlign = Paint.Align.CENTER
    }

    private val stateLabelText = mapOf(
        EliseState.LISTENING    to "EN ÉCOUTE",
        EliseState.RECORDING    to "J'ÉCOUTE...",
        EliseState.PROCESSING   to "TRAITEMENT...",
        EliseState.SPEAKING     to "ÉLISE PARLE",
        EliseState.ERROR        to "ERREUR",
        EliseState.IDLE         to "VEILLE",
        EliseState.NOT_CONFIGURED to "CONFIG. REQUISE",
    )

    // ── État ──────────────────────────────────────────────────────────────────
    var state: EliseState = EliseState.IDLE
        set(v) { field = v; updateColors(); invalidate() }

    var transcript: String = ""
        set(v) { field = v; invalidate() }

    private var colors = palette[EliseState.IDLE]!!
    private var time = 0f
    private var cx = 0f
    private var cy = 0f
    private var orbRadius = 0f
    private var lastFrame = System.nanoTime()

    init {
        initParticles()
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // nécessaire pour BlurMaskFilter
    }

    // ── Init particules ────────────────────────────────────────────────────────
    private fun initParticles() {
        particles.clear()
        val rng = Random(42)
        repeat(N_PARTICLES) { i ->
            particles += Particle(
                angle      = rng.nextFloat() * 2 * PI.toFloat(),
                radius     = if (i < N_CORE) rng.nextFloat() * 0.25f + 0.08f
                             else rng.nextFloat() * 0.55f + 0.28f,
                z          = rng.nextFloat() * 2f - 1f,
                speed      = (rng.nextFloat() * 0.6f + 0.2f) * (if (rng.nextBoolean()) 1f else -1f),
                size       = if (i < N_CORE) rng.nextFloat() * 3f + 1.5f
                             else rng.nextFloat() * 2.5f + 0.5f,
                waveOffset = rng.nextFloat() * 2 * PI.toFloat(),
                colorIdx   = i % 3,
            )
        }
    }

    private fun updateColors() {
        colors = palette[state] ?: palette[EliseState.IDLE]!!
    }

    // ── Render loop ────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now
        time += dt

        cx = width / 2f
        cy = height / 2f
        orbRadius = width * 0.13f

        val speed = when (state) {
            EliseState.LISTENING   -> 0.55f
            EliseState.RECORDING   -> 1.4f
            EliseState.PROCESSING  -> 1.8f
            EliseState.SPEAKING    -> 2.2f
            EliseState.ERROR       -> 0.2f
            else                   -> 0.1f
        }

        // Fond
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Lignes de grille en perspective (effet profondeur)
        drawGrid(canvas)

        // Particules
        for (p in particles) {
            p.angle += p.speed * dt * speed

            val waveAmp = when (state) {
                EliseState.LISTENING  -> 0.07f
                EliseState.SPEAKING   -> 0.14f
                EliseState.PROCESSING -> 0.10f
                else                  -> 0.03f
            }
            val waveR = p.radius + sin(time * 2.1f + p.waveOffset) * waveAmp
            val r = waveR * (width * 0.47f)

            // Projection 3D simple : Z influence taille et opacité
            val zFactor = (p.z + 1f) / 2f          // [0, 1]
            val projScale = 0.5f + zFactor * 0.5f  // [0.5, 1.0]
            val alpha = (0.25f + zFactor * 0.75f).coerceIn(0f, 1f)

            val x = cx + cos(p.angle) * r
            val y = cy + sin(p.angle) * r * 0.65f  // aplatissement axe Y = illusion 3D

            val particleColor = colors[p.colorIdx % colors.size]
            val a = (alpha * 255).toInt()

            // Halo flou (glow)
            glowPaint.color = Color.argb(a / 4, Color.red(particleColor),
                Color.green(particleColor), Color.blue(particleColor))
            canvas.drawCircle(x, y, p.size * projScale * 3.5f, glowPaint)

            // Particule principale
            paint.color = Color.argb(a, Color.red(particleColor),
                Color.green(particleColor), Color.blue(particleColor))
            canvas.drawCircle(x, y, p.size * projScale, paint)
        }

        // Orbe central
        drawCoreOrb(canvas)

        // Title — "ÉLISE" at top
        textPaint.textSize = width * 0.092f
        textPaint.color = Color.argb(200, 0, 229, 255)
        canvas.drawText("ÉLISE", cx, cy * 0.22f, textPaint)

        // State label below title
        val labelColor = colors[0]
        textPaint.textSize = width * 0.058f
        textPaint.color = Color.argb(210, Color.red(labelColor), Color.green(labelColor), Color.blue(labelColor))
        val label = stateLabelText[state] ?: ""
        canvas.drawText(label, cx, cy * 0.42f, textPaint)

        // Transcript below orb
        if (transcript.isNotEmpty() && state != EliseState.RECORDING) {
            statusPaint.textSize = width * 0.040f
            statusPaint.color = Color.argb(120, 90, 122, 153)
            val short = if (transcript.length > 28) transcript.take(28) + "…" else transcript
            canvas.drawText("\"$short\"", cx, cy * 1.92f, statusPaint)
        }

        // Prochain frame
        postInvalidateOnAnimation()
    }

    // ── Grille de fond (perspective) ──────────────────────────────────────────
    private fun drawGrid(canvas: Canvas) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 0, 229, 255)
            strokeWidth = 0.7f
            style = Paint.Style.STROKE
        }
        val horizon = cy * 0.55f
        val nLines = 7
        for (i in 0..nLines) {
            val t = i.toFloat() / nLines
            val y = horizon + (height - horizon) * t * t
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
        for (i in -4..4) {
            val x = cx + i * (width / 8f)
            canvas.drawLine(x, horizon, cx + i * width * 1.5f, height.toFloat(), gridPaint)
        }
    }

    // ── Noyau central ─────────────────────────────────────────────────────────
    private fun drawCoreOrb(canvas: Canvas) {
        val pulse = 1f + sin(time * 3.5f) * 0.08f
        val r = orbRadius * pulse

        // Couches de halo
        for (layer in 4 downTo 1) {
            val lr = r * (1f + layer * 0.55f)
            val la = (55 / layer).coerceAtLeast(6)
            glowPaint.color = Color.argb(la, Color.red(colors[0]),
                Color.green(colors[0]), Color.blue(colors[0]))
            canvas.drawCircle(cx, cy, lr, glowPaint)
        }

        // Gradient radial central
        val grad = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.WHITE, colors[0], Color.TRANSPARENT),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = grad
        canvas.drawCircle(cx, cy, r, corePaint)

        // Anneau
        corePaint.shader = null
        corePaint.color = colors[0]
        corePaint.style = Paint.Style.STROKE
        corePaint.strokeWidth = 1.5f
        corePaint.alpha = 160
        canvas.drawCircle(cx, cy, r * 1.25f, corePaint)
        corePaint.style = Paint.Style.FILL
        corePaint.alpha = 255
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initParticles()
    }
}
