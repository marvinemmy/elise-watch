package com.lnsgroup.elise.watch.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Animation d'arcs lumineux concentriques inspirée du Gemini Live orb.
 * Portée fidèlement depuis elise-watch.html (spec v1.0 — 23 mai 2026).
 * Fond noir AMOLED, arcs qui tournent en sens alternés, pulse + dégradé.
 */
class EliseParticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Définition d'un état d'animation ──────────────────────────────────────

    private data class AnimState(
        val r: Float, val g: Float, val b: Float,   // couleur cible RGB 0-255
        val arcs: Int,
        val speed: Float,
        val expandScale: Float,
        val pulseAmp: Float,
    )

    private val animStates = mapOf(
        EliseState.WAITING        to AnimState(192f, 132f, 252f, 3, 0.4f,  0.55f, 0.10f),  // #C084FC violet
        EliseState.LISTENING      to AnimState( 34f, 211f, 238f, 5, 1.2f,  0.95f, 0.30f),  // #22D3EE cyan
        EliseState.RECORDING      to AnimState( 34f, 211f, 238f, 5, 1.6f,  0.95f, 0.35f),  // cyan vif
        EliseState.PROCESSING     to AnimState(251f, 146f,  60f, 4, 0.8f,  0.75f, 0.20f),  // #FB923C orange
        EliseState.SPEAKING       to AnimState(250f, 204f,  21f, 4, 1.0f,  0.85f, 0.22f),  // #FACC15 jaune
        EliseState.ERROR          to AnimState(239f,  68f,  68f, 2, 1.5f,  0.50f, 0.35f),  // #EF4444 rouge
        EliseState.IDLE           to AnimState(192f, 132f, 252f, 3, 0.2f,  0.55f, 0.06f),  // violet dim
        EliseState.NOT_CONFIGURED to AnimState(239f,  68f,  68f, 2, 1.5f,  0.50f, 0.35f),  // rouge
    )

    // ── Définition d'un arc individuel ────────────────────────────────────────

    private data class ArcDef(
        val radiusFactor: Float,
        val thickness: Float,
        val opacity: Float,
        val phaseOffset: Float,
        val arcSpan: Float,
        val rotSpeed: Float,
        val blurMask: BlurMaskFilter,
    )

    // ── État courant ──────────────────────────────────────────────────────────

    var state: EliseState = EliseState.WAITING
        set(v) { field = v; onStateChanged() }

    private var arcDefs = listOf<ArcDef>()
    private var curAnimState = animStates[EliseState.WAITING]!!

    // Couleur interpolée (lerp frame-par-frame, comme le JS)
    private var lerpR = 192f; private var lerpG = 132f; private var lerpB = 252f

    private var time = 0f
    private var lastNs = System.nanoTime()
    private var cx = 0f; private var cy = 0f
    private val dp = context.resources.displayMetrics.density

    // ── Peintures (réutilisées) ───────────────────────────────────────────────

    private val bgPaint = Paint().apply { color = 0xFF0A0A0F.toInt() }
    private val glowPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val sharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    // gradient colors array — réutilisé pour éviter les allocations
    private val gc = intArrayOf(0, 0, 0)
    private val gp = floatArrayOf(0f, 0.5f, 1f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        onStateChanged()
    }

    private fun onStateChanged() {
        val s = animStates[state] ?: animStates[EliseState.WAITING]!!
        curAnimState = s
        arcDefs = (0 until s.arcs).map { i ->
            val frac = i.toFloat() / s.arcs
            val thick = (2.5f - frac * 1.0f) * dp
            ArcDef(
                radiusFactor = 0.22f + frac * 0.18f,
                thickness    = thick,
                opacity      = 0.9f  - frac * 0.35f,
                phaseOffset  = frac  * 2f * PI.toFloat(),
                arcSpan      = PI.toFloat() * (1.1f - frac * 0.2f),
                rotSpeed     = s.speed * (1f + frac * 0.4f) * (if (i % 2 == 0) 1f else -1f),
                blurMask     = BlurMaskFilter(thick * 2f, BlurMaskFilter.Blur.NORMAL),
            )
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt  = ((now - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
        lastNs  = now
        time   += dt

        val s = curAnimState

        // ── Lerp couleur (0.055 par frame, ~60fps — identique au JS) ──────────
        val lf = 0.055f
        lerpR += (s.r - lerpR) * lf
        lerpG += (s.g - lerpG) * lf
        lerpB += (s.b - lerpB) * lf
        val cr = lerpR.toInt().coerceIn(0, 255)
        val cg = lerpG.toInt().coerceIn(0, 255)
        val cb = lerpB.toInt().coerceIn(0, 255)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // ── Halo ambiant (radial, 7% opacité) ────────────────────────────────
        val ambR = minOf(width, height) * 0.48f
        ambientPaint.shader = RadialGradient(
            cx, cy, ambR,
            intArrayOf(Color.argb(18, cr, cg, cb), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, ambR, ambientPaint)

        // ── Arcs ───────────────────────────────────────────────────────────────
        val baseR = minOf(width, height) * 0.36f

        for (def in arcDefs) {
            val pulse  = s.pulseAmp * sin(time * s.speed * 2.2f + def.phaseOffset)
            val r      = baseR * def.radiusFactor * 7.5f * (s.expandScale + pulse)
            val angle  = time * def.rotSpeed + def.phaseOffset
            val startA = angle - def.arcSpan / 2f
            val endA   = angle + def.arcSpan / 2f
            val startDeg = Math.toDegrees(startA.toDouble()).toFloat()
            val sweepDeg = Math.toDegrees((endA - startA).toDouble()).toFloat()

            oval.set(cx - r, cy - r, cx + r, cy + r)

            // Passe 1 — halo flou, opacité 35%
            val glowAlpha = (def.opacity * 0.35f * 255f).toInt()
            glowPaint.color       = Color.argb(glowAlpha, cr, cg, cb)
            glowPaint.strokeWidth = def.thickness * 4f
            glowPaint.maskFilter  = def.blurMask
            canvas.drawArc(oval, startDeg, sweepDeg, false, glowPaint)

            // Passe 2 — trait net, dégradé transparent→couleur→transparent
            val x0 = cx + r * cos(startA); val y0 = cy + r * sin(startA)
            val x1 = cx + r * cos(endA);   val y1 = cy + r * sin(endA)
            gc[0] = Color.argb(0, cr, cg, cb)
            gc[1] = Color.argb((def.opacity * 255f).toInt(), cr, cg, cb)
            gc[2] = Color.argb(0, cr, cg, cb)
            sharpPaint.shader      = LinearGradient(x0, y0, x1, y1, gc, gp, Shader.TileMode.CLAMP)
            sharpPaint.strokeWidth = def.thickness
            sharpPaint.maskFilter  = null
            canvas.drawArc(oval, startDeg, sweepDeg, false, sharpPaint)
        }

        postInvalidateOnAnimation()
    }
}
