package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Samsung-music-island-style animated waveform — FOUR independent
 * channels each driven by its own composite harmonic + slow amplitude
 * modulator so the waves bounce / dip / surge out of phase like a real
 * audio waveform rather than rolling together. Pastel lilac → soft
 * violet palette, all semi-transparent so the layers blend smoothly.
 *
 * Drawing is clipped to a rounded-rect path matching the island's pill
 * background, so the wave bodies never bleed past the pill's corners
 * even on tall amplitude peaks.
 */
class IslandWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val clipPath = Path()
    private val clipRect = RectF()
    private var time = 0f

    /** Each channel composes three harmonics + a slow amp modulator.
     *  Order matters — list[0] paints FIRST (back), list[3] paints
     *  LAST (front). For all 4 layers to stay visible:
     *    • BACK waves get the LARGEST baseAmp + DEEPEST baselineY so
     *      their crests rise high above the front waves.
     *    • FRONT waves get the SMALLEST baseAmp + HIGHEST baselineY
     *      (closer to the top of the pill) so they sit as a narrow
     *      ribbon at the bottom and don't blanket the back ones.
     *  Combined with stepped alphas — back is ~60% opaque, front
     *  ~95% — the four bands read as overlapping music ribbons. */
    private val waves = listOf(
        Wave( // BACK — tallest, drawn first
            color           = 0x99B69EF9.toInt(), // soft lavender, 60% alpha
            baseFreq        = 1.6f,
            h2              = 2.7f, h3 = 3.5f,
            phaseSpeed      = 1.1f,
            baseAmp         = 0.85f,
            ampModFreq      = 0.45f, ampModPhase = 0.0f,
            baselineYFrac   = 0.88f,
        ),
        Wave(
            color           = 0xB3CABDFB.toInt(), // soft periwinkle, 70%
            baseFreq        = 1.9f,
            h2              = 2.4f, h3 = 4.1f,
            phaseSpeed      = 1.5f,
            baseAmp         = 0.70f,
            ampModFreq      = 0.60f, ampModPhase = 1.3f,
            baselineYFrac   = 0.82f,
        ),
        Wave(
            color           = 0xCCDDD6F3.toInt(), // pastel violet, 80%
            baseFreq        = 2.3f,
            h2              = 3.1f, h3 = 4.7f,
            phaseSpeed      = 0.9f,
            baseAmp         = 0.55f,
            ampModFreq      = 0.32f, ampModPhase = 2.5f,
            baselineYFrac   = 0.76f,
        ),
        Wave( // FRONT — shortest, drawn last
            color           = 0xE6EDE9FE.toInt(), // very light lilac, 90%
            baseFreq        = 2.8f,
            h2              = 3.6f, h3 = 5.2f,
            phaseSpeed      = 1.8f,
            baseAmp         = 0.40f,
            ampModFreq      = 0.78f, ampModPhase = 0.7f,
            baselineYFrac   = 0.70f,
        ),
    )

    // Animation rate dropped 4× then nudged 1.3× longer again — the
    // 240s sweep was the right ballpark but still on the brisk side
    // for an idle ambient vibe. 312s gives a noticeably lazier drift.
    private val animator = ValueAnimator.ofFloat(0f, 1000f).apply {
        duration = 312_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            time = it.animatedValue as Float
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

        // Clip to the pill's rounded-rect shape (radius = half height,
        // matching the island's bg_dynamic_island drawable). Without
        // this the wave fills paint past the rounded corners.
        clipRect.set(0f, 0f, w, h)
        val radius = min(h, w) * 0.5f
        clipPath.reset()
        clipPath.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val step = (w / 90f).coerceAtLeast(1f)
        val twoPi = (2f * PI).toFloat()

        for (wave in waves) {
            // Each wave has its OWN baseline Y (staggered top→bottom)
            // so the four layers don't collapse onto one line. Without
            // this stagger the front (biggest) wave blanketed the back
            // ones — only 2 read as visible.
            val baseline = h * wave.baselineYFrac
            // Slow amplitude modulator — the music-channel "bounce".
            // Ranges 0.35 → 1.0 so the wave never fully disappears
            // but DOES visibly rise / fall.
            val modAngle = time * wave.ampModFreq + wave.ampModPhase
            val ampMod = 0.35f + 0.65f * (0.5f + 0.5f * sin(modAngle))
            val amp = wave.baseAmp * ampMod * (h * 0.45f)

            paint.color = wave.color
            paint.style = Paint.Style.FILL
            path.reset()

            var x = 0f
            var firstPoint = true
            while (x <= w) {
                val t = x / w
                // Three harmonics with slightly different phase speeds
                // so they slide against each other and produce
                // constantly-changing peaks.
                val a1 = t * wave.baseFreq * twoPi + time * wave.phaseSpeed
                val a2 = t * wave.baseFreq * wave.h2 * twoPi + time * wave.phaseSpeed * 1.3f
                val a3 = t * wave.baseFreq * wave.h3 * twoPi + time * wave.phaseSpeed * 0.8f
                val composite = (
                    sin(a1.toDouble()) * 0.55 +
                    sin(a2.toDouble()) * 0.30 +
                    sin(a3.toDouble()) * 0.15
                ).toFloat()
                val y = baseline + composite * amp
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

        canvas.restore()
    }

    private data class Wave(
        val color: Int,
        val baseFreq: Float,
        val h2: Float,
        val h3: Float,
        val phaseSpeed: Float,
        val baseAmp: Float,
        val ampModFreq: Float,
        val ampModPhase: Float,
        /** Fraction of view height where this channel's centreline
         *  sits. 0 = top, 1 = bottom. Stagger across all 4 waves so
         *  the layers don't collapse onto each other. */
        val baselineYFrac: Float,
    )
}
