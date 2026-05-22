package com.lnsgroup.elise.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class EliseWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, RECORDING, PROCESSING, SPEAKING, ERROR }

    private var state = State.LISTENING
    private var amplitude = 0f
    private var phase = 0f
    private var scanY = 0f

    // ── State themes [primary, secondary, background] ─────────────────────────
    private val THEMES = mapOf(
        State.IDLE       to intArrayOf(0xFF9933FF.toInt(), 0xFF6600CC.toInt(), 0xFF0D0010.toInt()),
        State.LISTENING  to intArrayOf(0xFF00E5FF.toInt(), 0xFF0055FF.toInt(), 0xFF000D1A.toInt()),
        State.RECORDING  to intArrayOf(0xFF00FF88.toInt(), 0xFF00D4B0.toInt(), 0xFF001A0D.toInt()),
        State.PROCESSING to intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF140800.toInt()),
        State.SPEAKING   to intArrayOf(0xFFFFD700.toInt(), 0xFFFF7700.toInt(), 0xFF140F00.toInt()),
        State.ERROR      to intArrayOf(0xFFFF2255.toInt(), 0xFFFF0080.toInt(), 0xFF140010.toInt()),
    )

    private var curPrimary = 0xFF00E5FF.toInt()
    private var curSecondary = 0xFF0055FF.toInt()
    private var curBg = 0xFF000D1A.toInt()
    private var tgtPrimary = curPrimary
    private var tgtSecondary = curSecondary
    private var tgtBg = curBg

    // ── Particles ─────────────────────────────────────────────────────────────
    private val PC = 55
    private val pX = FloatArray(PC); private val pY = FloatArray(PC)
    private val pVX = FloatArray(PC); private val pVY = FloatArray(PC)
    private val pLife = FloatArray(PC); private val pMaxLife = FloatArray(PC)
    private val pSize = FloatArray(PC)
    private val pOrbitR = FloatArray(PC); private val pOrbitA = FloatArray(PC)
    private val pOrbitSpd = FloatArray(PC); private val pIsOrbit = BooleanArray(PC)
    private var particlesReady = false

    // ── Radial waveform ────────────────────────────────────────────────────────
    private val RN = 72
    private val radH = FloatArray(RN); private val radTarget = FloatArray(RN)

    // ── Linear bars ───────────────────────────────────────────────────────────
    private val BN = 7
    private val barH = FloatArray(BN) { 0.08f }
    private val barTgt = FloatArray(BN)
    private val barOff = FloatArray(BN) { it * (PI.toFloat() * 2f / BN) }

    // ── Orbital rings ─────────────────────────────────────────────────────────
    private val ON = 3
    private val orbitA = FloatArray(ON) { it * (PI.toFloat() * 2f / ON) }
    private val orbitSpd = floatArrayOf(0.010f, -0.014f, 0.008f)
    private val orbitRMult = floatArrayOf(0.24f, 0.32f, 0.40f)

    // ── Paints ────────────────────────────────────────────────────────────────
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blurFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blurStrokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val bgP = Paint()

    private val glow5 = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
    private val glow10 = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    private val glow20 = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    private val glow40 = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { doTick() }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        ticker.start()
    }

    fun setState(s: State) {
        if (s == state) return
        state = s
        val t = THEMES[s] ?: THEMES[State.LISTENING]!!
        tgtPrimary = t[0]; tgtSecondary = t[1]; tgtBg = t[2]
    }

    fun setAmplitude(rms: Float) { amplitude = (rms / 3200f).coerceIn(0f, 1f) }

    private fun doTick() {
        if (width == 0) return
        val cx = width / 2f; val cy = height * 0.44f

        phase = (phase + 0.038f) % (PI.toFloat() * 2f)
        scanY = (scanY + 2f) % height

        curPrimary = lerpC(curPrimary, tgtPrimary, 0.04f)
        curSecondary = lerpC(curSecondary, tgtSecondary, 0.04f)
        curBg = lerpC(curBg, tgtBg, 0.03f)

        for (i in 0 until ON) orbitA[i] += orbitSpd[i]

        if (!particlesReady && width > 0) {
            for (i in 0 until PC) resetP(i, cx, cy); particlesReady = true
        }
        for (i in 0 until PC) {
            pLife[i]++
            if (pLife[i] >= pMaxLife[i]) { resetP(i, cx, cy); continue }
            if (pIsOrbit[i]) {
                pOrbitA[i] += pOrbitSpd[i]
                val boost = if (state == State.RECORDING || state == State.SPEAKING) 1f + amplitude * 0.22f else 1f
                pX[i] = cx + cos(pOrbitA[i]) * pOrbitR[i] * boost
                pY[i] = cy + sin(pOrbitA[i]) * pOrbitR[i] * boost
            } else {
                val dx = cx - pX[i]; val dy = cy - pY[i]
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                pVX[i] += dx / d * 0.011f; pVY[i] += dy / d * 0.011f
                pVX[i] *= 0.986f; pVY[i] *= 0.986f
                pX[i] += pVX[i]; pY[i] += pVY[i]
            }
        }

        val rBase = when (state) {
            State.IDLE       -> 0.025f
            State.LISTENING  -> 0.07f + sin(phase) * 0.025f
            State.RECORDING  -> 0.09f + amplitude * 0.50f
            State.PROCESSING -> 0.06f + sin(phase * 2.5f) * 0.05f
            State.SPEAKING   -> 0.08f + amplitude * 0.40f
            State.ERROR      -> 0.04f + abs(sin(phase * 3f)) * 0.05f
        }
        for (i in 0 until RN) {
            val wOff = i.toFloat() / RN * PI.toFloat() * 4f
            val noise = if (state == State.RECORDING) Random.nextFloat() * amplitude * 0.12f else 0f
            radTarget[i] = (rBase + sin(phase * 1.8f + wOff).toFloat() * rBase * 0.65f + noise).coerceIn(0.01f, 0.55f)
            radH[i] += (radTarget[i] - radH[i]) * 0.22f
        }

        val bBase = when (state) {
            State.IDLE       -> 0.04f
            State.LISTENING  -> 0.11f + sin(phase) * 0.04f
            State.RECORDING  -> 0.14f + amplitude * 0.66f
            State.PROCESSING -> 0.09f + sin(phase * 2f) * 0.07f
            State.SPEAKING   -> 0.11f + amplitude * 0.50f
            State.ERROR      -> 0.05f + abs(sin(phase * 3f)) * 0.05f
        }
        for (i in 0 until BN) {
            barTgt[i] = (bBase + sin(phase + barOff[i]).toFloat() * bBase * 0.5f +
                    Random.nextFloat() * bBase * 0.08f).coerceIn(0.03f, 0.88f)
            barH[i] += (barTgt[i] - barH[i]) * 0.18f
        }

        invalidate()
    }

    private fun resetP(i: Int, cx: Float, cy: Float) {
        pIsOrbit[i] = Random.nextFloat() < 0.42f
        if (pIsOrbit[i]) {
            pOrbitR[i] = width * (0.12f + Random.nextFloat() * 0.30f)
            pOrbitA[i] = Random.nextFloat() * PI.toFloat() * 2f
            pOrbitSpd[i] = (0.006f + Random.nextFloat() * 0.012f) * (if (Random.nextBoolean()) 1f else -1f)
            pX[i] = cx + cos(pOrbitA[i]) * pOrbitR[i]; pY[i] = cy + sin(pOrbitA[i]) * pOrbitR[i]
            pVX[i] = 0f; pVY[i] = 0f
        } else {
            val angle = Random.nextFloat() * PI.toFloat() * 2f
            val dist = width * (0.08f + Random.nextFloat() * 0.42f)
            pX[i] = cx + cos(angle) * dist; pY[i] = cy + sin(angle) * dist
            pVX[i] = (Random.nextFloat() - 0.5f) * 0.4f; pVY[i] = (Random.nextFloat() - 0.5f) * 0.4f
        }
        pLife[i] = 0f; pMaxLife[i] = 70f + Random.nextFloat() * 100f
        pSize[i] = 1.2f + Random.nextFloat() * 2.8f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val cx = w / 2f; val cy = h * 0.44f

        // ── Background radial gradient ────────────────────────────────────────
        bgP.shader = RadialGradient(cx, cy, w * 0.78f,
            intArrayOf(ca(curPrimary, 0.10f), curBg, curBg),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgP)

        // ── Scan lines (PROCESSING) ────────────────────────────────────────────
        if (state == State.PROCESSING) {
            val sy = sin(scanY / h * PI.toFloat())
            strokeP.color = ca(curPrimary, sy * sy * 0.20f)
            strokeP.strokeWidth = 1.5f
            canvas.drawLine(0f, scanY, w, scanY, strokeP)
            val scanY2 = (scanY + h * 0.58f) % h
            val sy2 = sin(scanY2 / h * PI.toFloat())
            strokeP.color = ca(curPrimary, sy2 * sy2 * 0.10f)
            canvas.drawLine(0f, scanY2, w, scanY2, strokeP)
        }

        // ── Particles ─────────────────────────────────────────────────────────
        for (i in 0 until PC) {
            if (pMaxLife[i] == 0f) continue
            val lr = pLife[i] / pMaxLife[i]
            val sinLr = sin(lr * PI.toFloat())
            fillP.color = ca(if (lr < 0.5f) curPrimary else curSecondary, sinLr * sinLr * 0.75f)
            canvas.drawCircle(pX[i], pY[i], pSize[i] * (1f - lr * 0.25f), fillP)
        }

        // ── Ambient glow ──────────────────────────────────────────────────────
        val glowR = w * 0.42f * (1f + if (state == State.RECORDING || state == State.SPEAKING) amplitude * 0.25f else 0f)
        bgP.shader = RadialGradient(cx, cy, glowR,
            intArrayOf(ca(curPrimary, 0.18f), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowR, bgP)
        bgP.shader = null

        // ── Orbital rings ─────────────────────────────────────────────────────
        for (i in 0 until ON) {
            val ringR = w * orbitRMult[i]
            val dotX = cx + cos(orbitA[i]) * ringR
            val dotY = cy + sin(orbitA[i]) * ringR
            val baseAlpha = if (state == State.IDLE) 0.06f else 0.12f + i * 0.04f

            strokeP.color = ca(curPrimary, baseAlpha); strokeP.strokeWidth = 1f
            canvas.drawCircle(cx, cy, ringR, strokeP)

            fillP.color = ca(curPrimary, 0.65f + i * 0.12f)
            canvas.drawCircle(dotX, dotY, 3.5f + i.toFloat(), fillP)

            blurFillP.maskFilter = glow10
            blurFillP.color = ca(curPrimary, 0.22f)
            canvas.drawCircle(dotX, dotY, 9f + i * 2f, blurFillP)
            blurFillP.maskFilter = null
        }

        // ── Radial waveform ───────────────────────────────────────────────────
        val innerR = w * 0.110f; val maxBarLen = w * 0.115f
        strokeP.strokeWidth = 2.8f
        for (i in 0 until RN) {
            val angle = i.toFloat() / RN * PI.toFloat() * 2f
            val barLen = radH[i] * maxBarLen
            val x1 = cx + cos(angle) * innerR; val y1 = cy + sin(angle) * innerR
            val x2 = cx + cos(angle) * (innerR + barLen); val y2 = cy + sin(angle) * (innerR + barLen)
            val t = ((i.toFloat() / RN + phase / (PI.toFloat() * 2f)) % 1f)
            val barColor = lerpC(curPrimary, curSecondary, t)
            strokeP.color = ca(barColor, 0.50f + radH[i] * 0.85f)
            canvas.drawLine(x1, y1, x2, y2, strokeP)

            if (radH[i] > 0.15f) {
                blurStrokeP.maskFilter = glow5
                blurStrokeP.color = ca(barColor, radH[i] * 0.28f)
                canvas.drawLine(x1, y1, x2, y2, blurStrokeP)
                blurStrokeP.maskFilter = null
            }
        }

        // ── Central orb ───────────────────────────────────────────────────────
        val orbPulse = when (state) {
            State.RECORDING, State.SPEAKING -> amplitude * 0.30f
            State.PROCESSING -> abs(sin(phase * 3f)) * 0.14f
            else -> abs(sin(phase * 0.8f)) * 0.07f
        }
        val orbR = w * (0.068f + orbPulse)

        blurFillP.maskFilter = glow40
        blurFillP.color = ca(curPrimary, 0.12f)
        canvas.drawCircle(cx, cy, orbR * 3.2f, blurFillP)

        blurFillP.maskFilter = glow20
        blurFillP.color = ca(curPrimary, 0.18f)
        canvas.drawCircle(cx, cy, orbR * 2.0f, blurFillP)

        blurFillP.maskFilter = glow10
        blurFillP.color = ca(curPrimary, 0.28f)
        canvas.drawCircle(cx, cy, orbR * 1.45f, blurFillP)
        blurFillP.maskFilter = null

        fillP.shader = RadialGradient(
            cx - orbR * 0.22f, cy - orbR * 0.28f, orbR * 1.5f,
            intArrayOf(Color.WHITE, curPrimary, curSecondary),
            floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, orbR, fillP)
        fillP.shader = null

        fillP.color = ca(Color.WHITE, 0.22f)
        canvas.drawCircle(cx - orbR * 0.22f, cy - orbR * 0.30f, orbR * 0.21f, fillP)

        // ── Linear bars ───────────────────────────────────────────────────────
        val barsY = cy + w * 0.24f
        val totalW = w * 0.52f; val bw = totalW / BN
        val gap = bw * 0.38f; val abw = bw - gap
        val maxBH = h * 0.11f; val startX = cx - totalW / 2f + gap / 2f

        for (i in 0 until BN) {
            val bh = barH[i] * maxBH; val bx = startX + i * bw
            val top = barsY - bh / 2f; val bot = barsY + bh / 2f
            val t = (sin(phase + barOff[i]) * 0.5f + 0.5f).toFloat()
            val bc = lerpC(curPrimary, curSecondary, t)

            blurFillP.maskFilter = BlurMaskFilter(abw * 0.7f, BlurMaskFilter.Blur.NORMAL)
            blurFillP.color = ca(bc, 0.22f)
            canvas.drawRoundRect(bx, top, bx + abw, bot, abw / 2f, abw / 2f, blurFillP)
            blurFillP.maskFilter = null

            fillP.color = ca(bc, 0.88f)
            canvas.drawRoundRect(bx, top, bx + abw, bot, abw / 2f, abw / 2f, fillP)
        }
    }

    private fun lerpC(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt(),
            (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt(),
            (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt()
        )
    }

    private fun ca(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticker.cancel()
    }
}
