package com.lnsgroup.elise.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class EliseOverlayView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null

    private var state = EliseWaveView.State.LISTENING
    private var amplitude = 0f
    private var phase = 0f

    private val size = (64 * context.resources.displayMetrics.density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    private val stateColor = mapOf(
        EliseWaveView.State.LISTENING  to 0xFF00E5FF.toInt(),
        EliseWaveView.State.RECORDING  to 0xFF00FF88.toInt(),
        EliseWaveView.State.PROCESSING to 0xFFFF9500.toInt(),
        EliseWaveView.State.SPEAKING   to 0xFFFFD700.toInt(),
        EliseWaveView.State.ERROR      to 0xFFFF2255.toInt(),
        EliseWaveView.State.IDLE       to 0xFF1A3344.toInt(),
    )

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase += 0.04f
            if (phase > PI.toFloat() * 2f) phase -= PI.toFloat() * 2f
            invalidate()
        }
    }

    init {
        ticker.start()
    }

    fun setState(s: EliseWaveView.State) { state = s }
    fun setAmplitude(rms: Float) { amplitude = (rms / 4000f).coerceIn(0f, 1f) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val color = stateColor[state] ?: 0xFF00E5FF.toInt()

        val pulse = when (state) {
            EliseWaveView.State.RECORDING, EliseWaveView.State.SPEAKING -> amplitude * 0.35f
            else -> sin(phase).absoluteValue * 0.12f
        }
        val r = cx * (0.55f + pulse)

        // Glow
        glowPaint.color = (color and 0x00FFFFFF) or 0x55000000
        canvas.drawCircle(cx, cy, r * 1.4f, glowPaint)

        // Orbe principale
        val gradient = RadialGradient(
            cx, cy, r,
            intArrayOf(color, (color and 0x00FFFFFF) or 0xAA000000.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Contour lumineux
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = color
        paint.alpha = 180
        canvas.drawCircle(cx, cy, r, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val cx = width / 2f; val cy = height / 2f
            val dx = event.x - cx; val dy = event.y - cy
            if (dx * dx + dy * dy < (width / 2f) * (width / 2f)) {
                onTap?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticker.cancel()
    }
}
