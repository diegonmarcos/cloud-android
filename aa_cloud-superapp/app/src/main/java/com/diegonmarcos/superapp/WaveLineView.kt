package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Thin scrolling-sinusoid line drawn as the background of the
 * music-playing mini-island. Deliberately a DIFFERENT visual
 * language from the main Dynamic Island's [IslandWaveView] —
 * which is a 4-channel pastel violet→lilac waveform — so the
 * mini-island reads as related but distinct, in the spirit of
 * Samsung's now-playing strip (app icon + scrolling title +
 * subtle audio-line behind the text).
 *
 * Pattern — a single low-alpha line that is the sum of TWO
 * sinusoids of different frequencies + phase offsets:
 *   • low-frequency body wave  (slow, larger amplitude)
 *   • high-frequency carrier   (fast, small amplitude)
 * Result is a beating waveform that visually "breathes" without
 * being noisy enough to compete with the marquee text.
 *
 * Self-managing — animator starts in onAttachedToWindow + cancels
 * in onDetachedFromWindow, so the mini-island's GONE/VISIBLE flip
 * (driven by [NowPlayingMonitor]) automatically pauses redraws
 * when nothing is playing.
 */
class WaveLineView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(ctx, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.0f)
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(0xB0, 0xC4, 0xB5, 0xFD)
    }

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
        duration = 3200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isRunning) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cy = h / 2f
        val amp = h * 0.22f
        val freqBody = (2.0 * PI / w).toFloat() * 2.4f
        val freqCarrier = (2.0 * PI / w).toFloat() * 7.0f
        path.reset()
        path.moveTo(0f, cy)
        val step = dp(2.0f).coerceAtLeast(1.0f)
        var x = 0f
        while (x <= w) {
            val body = sin(x * freqBody + phase)
            val carrier = sin(x * freqCarrier - phase * 1.7f) * 0.30
            val y = cy + ((body + carrier) * amp).toFloat()
            path.lineTo(x, y)
            x += step
        }
        canvas.drawPath(path, paint)
    }
}
