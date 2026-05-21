package com.lnsgroup.elise.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.*
import kotlin.random.Random

class EliseWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, RECORDING, PROCESSING, SPEAKING, ERROR }

    private var state = State.LISTENING
    private var amplitude = 0f      // 0..1, réactif voix
    private var phase = 0f          // phase animation idle

    // Couleurs par état
    private val colors = mapOf(
        State.IDLE       to intArrayOf(0xFF1A3344.toInt(), 0xFF0D2233.toInt()),
        State.LISTENING  to intArrayOf(0xFF00E5FF.toInt(), 0xFF0066FF.toInt(), 0xFF00BFFF.toInt()),
        State.RECORDING  to intArrayOf(0xFF00FF88.toInt(), 0xFF00E5C0.toInt(), 0xFF00FF44.toInt()),
        State.PROCESSING to intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFFFF6600.toInt()),
        State.SPEAKING   to intArrayOf(0xFFFFD700.toInt(), 0xFFFF9500.toInt(), 0xFFFF4444.toInt()),
        State.ERROR      to intArrayOf(0xFFFF2255.toInt(), 0xFFFF0080.toInt()),
    )

    // 5 barres comme Gemini — chaque barre a une hauteur cible indépendante
    private val N = 5
    private val barH = FloatArray(N) { 0.08f }
    private val targetH = FloatArray(N) { 0.08f }
    private val phaseOff = FloatArray(N) { it * (PI.toFloat() * 2f / N) }

    // Paint
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#010A10") }

    // Animateur continu
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16L
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { tick() }
    }

    init { ticker.start() }

    fun setState(s: State) { state = s }
    fun setAmplitude(rms: Float) { amplitude = (rms / 4000f).coerceIn(0f, 1f) }

    private fun tick() {
        phase += 0.035f
        if (phase > PI.toFloat() * 2f) phase -= PI.toFloat() * 2f

        val base = when (state) {
            State.IDLE       -> 0.04f
            State.LISTENING  -> 0.12f + sin(phase) * 0.04f
            State.RECORDING  -> 0.15f + amplitude * 0.70f
            State.PROCESSING -> 0.10f + sin(phase * 2f) * 0.08f
            State.SPEAKING   -> 0.12f + amplitude * 0.55f
            State.ERROR      -> 0.06f + sin(phase * 3f).absoluteValue * 0.06f
        }

        for (i in 0 until N) {
            val wave = sin(phase + phaseOff[i]).toFloat()
            targetH[i] = (base + wave * base * 0.5f + Random.nextFloat() * base * 0.1f)
                .coerceIn(0.03f, 0.92f)
            barH[i] += (targetH[i] - barH[i]) * 0.18f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Fond
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val palette = colors[state] ?: colors[State.LISTENING]!!

        // Halo central
        val haloR = w * 0.28f
        val haloAlpha = when (state) {
            State.IDLE -> 20
            State.LISTENING -> (30 + sin(phase) * 15).toInt()
            State.RECORDING -> (40 + amplitude * 60).toInt()
            State.PROCESSING -> (35 + sin(phase * 2) * 20).toInt()
            State.SPEAKING -> (40 + amplitude * 50).toInt()
            State.ERROR -> 25
        }
        val grad = RadialGradient(cx, cy, haloR,
            intArrayOf(
                (palette[0] and 0x00FFFFFF) or (haloAlpha shl 24),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        glowPaint.shader = grad
        canvas.drawCircle(cx, cy, haloR, glowPaint)
        glowPaint.shader = null

        // Orbe central
        val orbR = w * 0.055f + sin(phase) * w * 0.008f
        paint.style = Paint.Style.FILL
        paint.color = palette[0]
        paint.alpha = 220
        canvas.drawCircle(cx, cy, orbR, paint)

        // 5 barres waveform
        val totalBarW = w * 0.55f
        val barW = totalBarW / N.toFloat()
        val gap = barW * 0.35f
        val actualBarW = barW - gap
        val maxBarH = h * 0.38f
        val startX = cx - totalBarW / 2f + gap / 2f

        for (i in 0 until N) {
            val bh = barH[i] * maxBarH
            val bx = startX + i * barW
            val top = cy - bh / 2f
            val bot = cy + bh / 2f

            val colorIdx = i % palette.size
            val nextIdx = (i + 1) % palette.size
            val t = (sin(phase + phaseOff[i]) * 0.5f + 0.5f)
            val blended = blendColor(palette[colorIdx], palette[nextIdx], t)

            // Glow
            glowPaint.color = blended and 0x00FFFFFF or 0x44000000
            glowPaint.maskFilter = BlurMaskFilter(actualBarW * 0.8f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(bx, top, bx + actualBarW, bot, actualBarW / 2f, actualBarW / 2f, glowPaint)

            // Barre principale
            paint.color = blended
            paint.alpha = 210
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(bx, top, bx + actualBarW, bot, actualBarW / 2f, actualBarW / 2f, paint)
        }
    }

    private fun blendColor(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) * (1 - t) + Color.red(c2) * t).toInt()
        val g = (Color.green(c1) * (1 - t) + Color.green(c2) * t).toInt()
        val b = (Color.blue(c1) * (1 - t) + Color.blue(c2) * t).toInt()
        return Color.rgb(r, g, b)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticker.cancel()
    }
}
