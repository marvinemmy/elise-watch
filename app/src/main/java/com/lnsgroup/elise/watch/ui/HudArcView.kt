package com.lnsgroup.elise.watch.ui

import android.content.Context
import android.graphics.*
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import com.lnsgroup.elise.watch.health.HealthSnapshot
import java.util.Calendar
import kotlin.math.*

class HudArcView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class GH { GOOD_HIGH, BAD_HIGH, NEUTRAL }

    private data class ArcDef(
        val centerAngle: Float,
        val subLabel: String,
        val goodHigh: GH,
        val sweep: Float = 20f,
    )

    private val N = 14

    private val arcDefs = run {
        val step = 360f / 14
        listOf(
            ArcDef((270f +  0 * step + 720f) % 360f, "HEURE",  GH.NEUTRAL),
            ArcDef((270f +  1 * step + 720f) % 360f, "BPM",    GH.GOOD_HIGH),
            ArcDef((270f +  2 * step + 720f) % 360f, "BATT",   GH.GOOD_HIGH),
            ArcDef((270f +  3 * step + 720f) % 360f, "NRG",    GH.GOOD_HIGH),
            ArcDef((270f +  4 * step + 720f) % 360f, "METEO",  GH.NEUTRAL),
            ArcDef((270f +  5 * step + 720f) % 360f, "PAS",    GH.GOOD_HIGH),
            ArcDef((270f +  6 * step + 720f) % 360f, "DATE",   GH.NEUTRAL),
            ArcDef((270f +  7 * step + 720f) % 360f, "TRAV",   GH.NEUTRAL),
            ArcDef((270f +  8 * step + 720f) % 360f, "MAILS",  GH.BAD_HIGH),
            ArcDef((270f +  9 * step + 720f) % 360f, "CALME",  GH.GOOD_HIGH),
            ArcDef((270f + 10 * step + 720f) % 360f, "STRESS", GH.BAD_HIGH),
            ArcDef((270f + 11 * step + 720f) % 360f, "SEREN",  GH.GOOD_HIGH),
            ArcDef((270f + 12 * step + 720f) % 360f, "MEM IA", GH.BAD_HIGH),
            ArcDef((270f + 13 * step + 720f) % 360f, "RDV",    GH.GOOD_HIGH),
        )
    }

    private val animFill   = FloatArray(N) { 0.4f }
    private val targetFill = FloatArray(N) { 0.4f }
    private val arcValues  = Array(N) { "—" }
    private var snap: HealthSnapshot? = null

    private val dp = context.resources.displayMetrics.density
    private val sw = 7f * dp

    private val oval   = RectF()
    private var arcR   = 0f
    private var cx     = 0f
    private var cy     = 0f
    private var rVal   = 0f
    private var rSub   = 0f
    private var badgeY = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = sw
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeWidth = sw * 1.9f
        maskFilter = BlurMaskFilter(12f * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = sw
    }
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 8.5f * dp
    }
    private val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 5.5f * dp
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 20f * dp
    }
    private val scoreSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 7f * dp
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx    = w / 2f; cy = h / 2f
        val r = minOf(w, h) / 2f
        arcR  = r - sw / 2f - 2f * dp
        oval.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        rVal   = arcR - 19f * dp
        rSub   = arcR - 31f * dp
        badgeY = cy + arcR * 0.43f
    }

    fun update(s: HealthSnapshot) {
        snap = s
        val cal    = Calendar.getInstance()
        val hour   = cal.get(Calendar.HOUR_OF_DAY)
        val min    = cal.get(Calendar.MINUTE)
        val day    = cal.get(Calendar.DAY_OF_MONTH)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val month  = cal.get(Calendar.MONTH)
        val batt   = getBatteryPct()
        val months = arrayOf("JAN","FEV","MAR","AVR","MAI","JUN","JUL","AOU","SEP","OCT","NOV","DEC")

        targetFill[0]  = (hour * 60 + min) / (24f * 60f)
        targetFill[1]  = hrFill(s.heartRateBpm)
        targetFill[2]  = batt / 100f
        targetFill[3]  = 1f - s.fatigueLevel / 100f
        targetFill[4]  = 0.5f
        targetFill[5]  = (s.stepsSinceStart / 8000f).coerceIn(0f, 1f)
        targetFill[6]  = day.toFloat() / maxDay
        targetFill[7]  = (s.activeMinutes / 480f).coerceIn(0f, 1f)
        targetFill[8]  = 0f
        targetFill[9]  = 1f - s.stressLevel / 100f
        targetFill[10] = s.stressLevel / 100f
        targetFill[11] = 1f - s.agitationLevel / 100f
        targetFill[12] = 0.4f
        targetFill[13] = 0.5f

        arcValues[0]  = "%02d:%02d".format(hour, min)
        arcValues[1]  = if (s.heartRateBpm > 0) "${s.heartRateBpm}" else "—"
        arcValues[2]  = "$batt%"
        arcValues[3]  = "${(100 - s.fatigueLevel.toInt())}%"
        arcValues[4]  = "—"
        arcValues[5]  = if (s.stepsSinceStart > 999) "${s.stepsSinceStart / 1000}k" else "${s.stepsSinceStart}"
        arcValues[6]  = "$day ${months[month]}"
        arcValues[7]  = run {
            val h2 = s.activeMinutes / 60; val m2 = s.activeMinutes % 60
            if (h2 > 0) "${h2}h${if (m2 > 0) "$m2" else ""}" else "${m2}m"
        }
        arcValues[8]  = "0"
        arcValues[9]  = "${(100 - s.stressLevel.toInt())}%"
        arcValues[10] = "${s.stressLevel.toInt()}%"
        arcValues[11] = "${(100 - s.agitationLevel.toInt())}%"
        arcValues[12] = "—"
        arcValues[13] = "—"

        invalidate()
    }

    private fun getBatteryPct(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50
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

    // green (hue 120) → yellow → orange → red (hue 0) based on how "bad" the value is
    private fun dynColor(fill: Float, gh: GH): Int {
        if (gh == GH.NEUTRAL) return 0xFF00D4FF.toInt()
        val bad  = if (gh == GH.GOOD_HIGH) 1f - fill else fill
        val hDeg = (120f * (1f - bad)).roundToInt().coerceIn(0, 120)
        val l    = when { bad > 0.75f -> 0.55f; bad > 0.50f -> 0.52f; else -> 0.50f }
        return hslToArgb(hDeg, 1f, l)
    }

    private fun hslToArgb(hDeg: Int, s: Float, l: Float): Int {
        val h = hDeg / 360f
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val r = (hue2rgb(p, q, h + 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
        val g = (hue2rgb(p, q, h) * 255f).roundToInt().coerceIn(0, 255)
        val b = (hue2rgb(p, q, h - 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }

    private fun hue2rgb(p: Float, q: Float, t: Float): Float {
        var t2 = t
        if (t2 < 0f) t2 += 1f
        if (t2 > 1f) t2 -= 1f
        return when {
            t2 < 1f / 6f -> p + (q - p) * 6f * t2
            t2 < 0.5f    -> q
            t2 < 2f / 3f -> p + (q - p) * (2f / 3f - t2) * 6f
            else          -> p
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    // Each character placed individually along the arc tangent.
    // Bottom-half arcs: characters reversed so text reads left→right from outside.
    private fun drawCurvedText(canvas: Canvas, text: String, r: Float, centerDeg: Float, paint: Paint) {
        if (text.isEmpty()) return
        val cRad    = Math.toRadians(centerDeg.toDouble()).toFloat()
        val onBot   = sin(cRad) > 0.001f
        val chars   = text.toList()
        val spacing = 0.5f * dp
        val widths  = chars.map { paint.measureText(it.toString()) + spacing }
        val totAng  = widths.sum() / r

        val dc  = if (onBot) chars.reversed() else chars
        val dw  = if (onBot) widths.reversed() else widths
        var ang = if (onBot) cRad + totAng / 2f else cRad - totAng / 2f
        val dir = if (onBot) -1f else 1f
        val yOff = -(paint.ascent() + paint.descent()) / 2f

        for (i in dc.indices) {
            ang += dir * dw[i] / (2f * r)
            canvas.save()
            canvas.translate(cx + cos(ang) * r, cy + sin(ang) * r)
            canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat() + if (onBot) -90f else 90f)
            canvas.drawText(dc[i].toString(), 0f, yOff, paint)
            canvas.restore()
            ang += dir * dw[i] / (2f * r)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = snap ?: return

        var needAnim = false
        for (i in 0 until N) {
            val d = targetFill[i] - animFill[i]
            if (abs(d) > 0.001f) { animFill[i] += d * 0.10f; needAnim = true }
        }

        for (i in 0 until N) {
            val def   = arcDefs[i]
            val fill  = animFill[i].coerceIn(0f, 1f)
            val color = dynColor(fill, def.goodHigh)
            val start = def.centerAngle - def.sweep / 2f

            trackPaint.color = withAlpha(color, 0.12f)
            canvas.drawArc(oval, start, def.sweep, false, trackPaint)

            if (fill > 0.01f) {
                glowPaint.color = withAlpha(color, 0.30f)
                canvas.drawArc(oval, start, def.sweep * fill, false, glowPaint)
                fillPaint.color = color
                canvas.drawArc(oval, start, def.sweep * fill, false, fillPaint)
            }

            valPaint.color = color
            drawCurvedText(canvas, arcValues[i], rVal, def.centerAngle, valPaint)

            lblPaint.color = withAlpha(color, 0.42f)
            drawCurvedText(canvas, def.subLabel, rSub, def.centerAngle, lblPaint)
        }

        scorePaint.color = s.scoreColor
        canvas.drawText("${s.socialScore}", cx, badgeY, scorePaint)
        scoreSubPaint.color = 0x52FFFFFF.toInt()
        canvas.drawText(s.scoreLabel, cx, badgeY + 11f * dp, scoreSubPaint)

        if (needAnim) postInvalidateOnAnimation()
    }
}
