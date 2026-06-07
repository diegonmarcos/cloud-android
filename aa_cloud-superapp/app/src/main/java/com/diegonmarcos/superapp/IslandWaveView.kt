package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Samsung-music-island-style animated waveform — three stacked sine
 * bands in a violet→lilac gradient that drift across the view with a
 * continuous phase shift. Drop-in replacement for the previous
 * "DCM · Apps" text label inside the toolbar Dynamic Island.
 *
 * Each band has its own amplitude, frequency and phase offset so the
 * crests never align — the visual reads as overlapping ribbons of
 * sound rather than one fat wave.
 */
class IslandWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var phase = 0f

    /** Back-to-front order. The first entry paints furthest back (deep
     *  purple, smallest amplitude); each subsequent entry sits in front
     *  (brighter, taller). Colors carry their own alpha so the
     *  overlaps blend into a soft purple gradient overall. */
    private val waves = listOf(
        Wave(color = 0xCC4C1D95.toInt(), amplitude = 0.32f, freq = 1.8f, phaseOff = 0f),
        Wave(color = 0xDD7C3AED.toInt(), amplitude = 0.42f, freq = 2.3f, phaseOff = 1.0f),
        Wave(color = 0xEEC084FC.toInt(), amplitude = 0.55f, freq = 2.9f, phaseOff = 2.0f),
    )

    private val animator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
        duration = 5000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Anchor the wave centreline at ~55% from the top — leaves a
        // tiny visual margin under the title row that used to sit
        // above. The bottom of every band closes to the view bottom so
        // the colour fills the lower half rather than printing as
        // free-floating squiggles.
        val baseline = h * 0.55f
        val step = (w / 80f).coerceAtLeast(1f)

        for (wave in waves) {
            paint.color = wave.color
            paint.style = Paint.Style.FILL
            path.reset()
            val amp = wave.amplitude * h * 0.45f
            var x = 0f
            var firstPoint = true
            while (x <= w) {
                val angle = (x / w) * wave.freq * 2f * PI.toFloat() + phase + wave.phaseOff
                val y = baseline + sin(angle.toDouble()).toFloat() * amp
                if (firstPoint) {
                    path.moveTo(x, y); firstPoint = false
                } else {
                    path.lineTo(x, y)
                }
                x += step
            }
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private data class Wave(
        val color: Int,
        val amplitude: Float,
        val freq: Float,
        val phaseOff: Float,
    )
}
