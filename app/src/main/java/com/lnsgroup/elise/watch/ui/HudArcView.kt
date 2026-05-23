package com.lnsgroup.elise.watch.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.lnsgroup.elise.watch.health.HealthSnapshot
import kotlin.math.*

/**
 * Overlay transparent qui dessine 6 arcs lumineux sur le contour circulaire du cadran.
 * Chaque arc représente une métrique santé. Score social affiché en bas.
 * Placé au-dessus de EliseParticleView dans le FrameLayout.
 */
class HudArcView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class ArcDef(
        val centerAngle: Float,    // degrés canvas (0=droite, sens horaire)
        val sweep: Float = 42f,
        val trackColor: Int,
        val fillColor: Int,
        val shortLabel: String,
    )

    // 6 arcs espacés de 60°, sweep 42°, gap 18°
    // Positions horaires: 12h · 2h · 4h · 6h · 8h · 10h
    private val arcDefs = listOf(
        ArcDef(270f, trackColor = 0x22FF4488, fillColor = 0xFFFF4488.toInt(), shortLabel = "BPM"),   // 12h – rythme cardiaque
        ArcDef(330f, trackColor = 0x220099FF, fillColor = 0xFF0099FF.toInt(), shortLabel = "NRG"),   // 2h  – énergie (inv. fatigue)
        ArcDef( 30f, trackColor = 0x2200EEBB, fillColor = 0xFF00EEBB.toInt(), shortLabel = "PAS"),   // 4h  – activité (steps)
        ArcDef( 90f, trackColor = 0x22AA55FF, fillColor = 0xFFAA55FF.toInt(), shortLabel = "TRAV"),  // 6h  – temps actif
        ArcDef(150f, trackColor = 0x22FF9500, fillColor = 0xFFFF9500.toInt(), shortLabel = "CLM"),   // 8h  – calme (inv. stress)
        ArcDef(210f, trackColor = 0x22FFDD00, fillColor = 0xFFFFDD00.toInt(), shortLabel = "SRN"),   // 10h – sérénité (inv. agitation)
    )

    private val animFill   = FloatArray(6) { 0.4f }
    private val targetFill = FloatArray(6) { 0.4f }
    private var snap: HealthSnapshot? = null

    private val dp = context.resources.displayMetrics.density
    private val sw = 8f * dp   // épaisseur arc

    // Layout calculé à onSizeChanged
    private val oval        = RectF()
    private var arcR        = 0f
    private var cx          = 0f
    private var cy          = 0f
    private val labelPos    = Array(6) { FloatArray(2) }
    private var badgeY      = 0f

    // Peintures (une seule instance de chaque, réutilisées)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = sw
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeWidth = sw * 1.6f
        maskFilter = BlurMaskFilter(10f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = sw
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 8f * dp
    }
    private val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 5.5f * dp
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 18f * dp
    }
    private val scoreSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 6.5f * dp; color = 0x88FFFFFF.toInt()
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        val r = minOf(w, h) / 2f
        arcR = r - sw / 2f - 2f * dp
        oval.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        badgeY = cy + r * 0.58f

        val labelR = arcR - sw * 1.9f
        for (i in 0..5) {
            val rad = Math.toRadians(arcDefs[i].centerAngle.toDouble())
            labelPos[i][0] = cx + cos(rad).toFloat() * labelR
            labelPos[i][1] = cy + sin(rad).toFloat() * labelR
        }
    }

    fun update(s: HealthSnapshot) {
        snap = s
        targetFill[0] = hrFill(s.heartRateBpm)
        targetFill[1] = 1f - s.fatigueLevel / 100f
        targetFill[2] = (s.stepsSinceStart / 8000f).coerceIn(0f, 1f)
        targetFill[3] = (s.activeMinutes / 480f).coerceIn(0f, 1f)   // 8h = plein
        targetFill[4] = 1f - s.stressLevel / 100f
        targetFill[5] = 1f - s.agitationLevel / 100f
        invalidate()
    }

    private fun hrFill(bpm: Int): Float = when {
        bpm <= 0   -> 0.4f
        bpm < 50   -> 0.15f
        bpm <= 68  -> 1.0f
        bpm <= 80  -> 0.82f
        bpm <= 92  -> 0.58f
        bpm <= 108 -> 0.30f
        else       -> 0.10f
    }

    override fun onDraw(canvas: Canvas) {
        val s = snap ?: run { postInvalidateOnAnimation(); return }

        var needAnim = false
        for (i in 0..5) {
            val d = targetFill[i] - animFill[i]
            if (abs(d) > 0.001f) { animFill[i] += d * 0.10f; needAnim = true }
        }

        for (i in 0..5) {
            val def  = arcDefs[i]
            val fill = animFill[i].coerceIn(0f, 1f)
            val startDeg = def.centerAngle - def.sweep / 2f

            // Piste de fond
            trackPaint.color = def.trackColor
            canvas.drawArc(oval, startDeg, def.sweep, false, trackPaint)

            if (fill > 0.01f) {
                // Halo lumineux
                glowPaint.color = (def.fillColor and 0x00FFFFFF) or 0x30000000
                canvas.drawArc(oval, startDeg, def.sweep * fill, false, glowPaint)

                // Arc coloré
                fillPaint.color = def.fillColor
                canvas.drawArc(oval, startDeg, def.sweep * fill, false, fillPaint)
            }

            // Valeur numérique + libellé au cœur de l'arc
            val lx = labelPos[i][0]
            val ly = labelPos[i][1]

            numPaint.color = def.fillColor
            canvas.drawText(arcValue(i, s), lx, ly + 3f * dp, numPaint)

            lblPaint.color = (def.fillColor and 0x00FFFFFF) or 0x77000000
            canvas.drawText(def.shortLabel, lx, ly + 10.5f * dp, lblPaint)
        }

        // Badge score social en bas
        scorePaint.color = s.scoreColor
        canvas.drawText("${s.socialScore}", cx, badgeY, scorePaint)
        canvas.drawText(s.scoreLabel, cx, badgeY + 9f * dp, scoreSubPaint)

        if (needAnim) postInvalidateOnAnimation()
    }

    private fun arcValue(idx: Int, s: HealthSnapshot): String = when (idx) {
        0 -> if (s.heartRateBpm > 0) "${s.heartRateBpm}" else "—"
        1 -> "${(100 - s.fatigueLevel.toInt())}%"
        2 -> if (s.stepsSinceStart > 999) "${s.stepsSinceStart / 1000}k" else "${s.stepsSinceStart}"
        3 -> {
            val h = s.activeMinutes / 60; val m = s.activeMinutes % 60
            if (h > 0) "${h}h${if (m > 0) "$m" else ""}" else "${m}m"
        }
        4 -> "${(100 - s.stressLevel.toInt())}%"
        5 -> "${(100 - s.agitationLevel.toInt())}%"
        else -> "—"
    }
}
