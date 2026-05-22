package com.lnsgroup.elise.watch.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class EliseParticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Palette par état ───────────────────────────────────────────────────────
    // [primary, secondary, accent]
    private val palette = mapOf(
        EliseState.WAITING      to intArrayOf(0xFFAA44FF.toInt(), 0xFFFF44AA.toInt(), 0xFF882299.toInt()),
        EliseState.LISTENING    to intArrayOf(0xFF00FFFF.toInt(), 0xFF00DDFF.toInt(), 0xFF44FFFF.toInt()),
        EliseState.RECORDING    to intArrayOf(0xFF00FFFF.toInt(), 0xFF00DDFF.toInt(), 0xFF44FFFF.toInt()),
        EliseState.PROCESSING   to intArrayOf(0xFFFF9500.toInt(), 0xFFFF6600.toInt(), 0xFFFFBB00.toInt()),
        EliseState.SPEAKING     to intArrayOf(0xFFFFDD00.toInt(), 0xFFFFAA00.toInt(), 0xFFFFFF44.toInt()),
        EliseState.ERROR        to intArrayOf(0xFFFF2244.toInt(), 0xFFCC0033.toInt(), 0xFFFF6677.toInt()),
        EliseState.IDLE         to intArrayOf(0xFF1A3344.toInt(), 0xFF0D2233.toInt(), 0xFF243344.toInt()),
        EliseState.NOT_CONFIGURED to intArrayOf(0xFFFF2255.toInt(), 0xFF880022.toInt(), 0xFFFF0040.toInt()),
    )

    // ── Particules ─────────────────────────────────────────────────────────────
    private data class Particle(
        var angle: Float,
        var radius: Float,
        var z: Float,
        var speed: Float,
        var size: Float,
        var waveOffset: Float,
        var colorIdx: Int,
    )

    private val particles = mutableListOf<Particle>()
    private val N_PARTICLES = 90
    private val N_CORE = 18

    // ── Peintures ──────────────────────────────────────────────────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#010A10") }

    // ── État ───────────────────────────────────────────────────────────────────
    var state: EliseState = EliseState.WAITING
        set(v) { field = v; updateColors(); invalidate() }

    private var colors = palette[EliseState.WAITING]!!
    private var time = 0f
    private var cx = 0f
    private var cy = 0f
    private var orbRadius = 0f
    private var lastFrame = System.nanoTime()

    init {
        initParticles()
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

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
        colors = palette[state] ?: palette[EliseState.WAITING]!!
    }

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
            EliseState.WAITING     -> 0.18f
            EliseState.LISTENING   -> 0.55f
            EliseState.RECORDING   -> 1.4f
            EliseState.PROCESSING  -> 1.8f
            EliseState.SPEAKING    -> 2.2f
            EliseState.ERROR       -> 0.2f
            else                   -> 0.1f
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawGrid(canvas)

        for (p in particles) {
            p.angle += p.speed * dt * speed

            val waveAmp = when (state) {
                EliseState.LISTENING  -> 0.07f
                EliseState.SPEAKING   -> 0.14f
                EliseState.PROCESSING -> 0.10f
                EliseState.RECORDING  -> 0.09f
                else                  -> 0.03f
            }
            val waveR = p.radius + sin(time * 2.1f + p.waveOffset) * waveAmp
            val r = waveR * (width * 0.47f)

            val zFactor = (p.z + 1f) / 2f
            val projScale = 0.5f + zFactor * 0.5f
            val alpha = (0.25f + zFactor * 0.75f).coerceIn(0f, 1f)

            val x = cx + cos(p.angle) * r
            val y = cy + sin(p.angle) * r * 0.65f

            val particleColor = colors[p.colorIdx % colors.size]
            val a = (alpha * 255).toInt()

            glowPaint.color = Color.argb(a / 4, Color.red(particleColor),
                Color.green(particleColor), Color.blue(particleColor))
            canvas.drawCircle(x, y, p.size * projScale * 3.5f, glowPaint)

            paint.color = Color.argb(a, Color.red(particleColor),
                Color.green(particleColor), Color.blue(particleColor))
            canvas.drawCircle(x, y, p.size * projScale, paint)
        }

        drawCoreOrb(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawGrid(canvas: Canvas) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(14, Color.red(colors[0]), Color.green(colors[0]), Color.blue(colors[0]))
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

    private fun drawCoreOrb(canvas: Canvas) {
        val pulse = 1f + sin(time * 3.5f) * 0.08f
        val r = orbRadius * pulse

        for (layer in 4 downTo 1) {
            val lr = r * (1f + layer * 0.55f)
            val la = (55 / layer).coerceAtLeast(6)
            glowPaint.color = Color.argb(la, Color.red(colors[0]),
                Color.green(colors[0]), Color.blue(colors[0]))
            canvas.drawCircle(cx, cy, lr, glowPaint)
        }

        val grad = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.WHITE, colors[0], Color.TRANSPARENT),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = grad
        canvas.drawCircle(cx, cy, r, corePaint)

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
