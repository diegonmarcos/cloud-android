package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural galaxy backdrop — twinkling stars + occasional swept
 * comets — drawn behind the wireframe cube. Pure Canvas; cheap to
 * run; no asset cost. Seeded so the star field is identical across
 * launches (stable visual anchor).
 */
class GalaxyBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
    private data class Comet(val phase: Float, val angle: Float, val length: Float)

    private val rng = Random(20260531L)
    private val stars = (0..220).map {
        Star(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            r = rng.nextFloat() * 2.2f + 0.4f,
            phase = rng.nextFloat() * (Math.PI * 2f).toFloat(),
        )
    }
    private val comets = (0..3).map {
        Comet(
            phase = rng.nextFloat(),
            angle = rng.nextFloat() * (Math.PI * 2f).toFloat(),
            length = 90f + rng.nextFloat() * 140f,
        )
    }

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9D8FD.toInt()
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f
        color = 0xCCB794F4.toInt()
    }

    private var time = 0f

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                time = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        // Stars — twinkle by modulating alpha with their phase.
        for (s in stars) {
            val twinkle = (sin(time * 12f + s.phase) * 0.4f + 0.6f).absoluteValue
            starPaint.alpha = (twinkle * 220f).toInt().coerceIn(40, 255)
            canvas.drawCircle(s.x * w, s.y * h, s.r, starPaint)
        }
        // Comets — each comet has its own phase offset. Travels
        // diagonally across the field; visible only during the active
        // segment of its phase.
        for (c in comets) {
            val t = (time + c.phase) % 1f
            // Only render comet during the first 25% of its phase.
            if (t > 0.25f) continue
            val progress = t / 0.25f
            val cx = (-0.2f + progress * 1.4f) * w
            val cy = h * (0.15f + 0.6f * c.phase)
            val dx = Math.cos(c.angle.toDouble()).toFloat() * c.length
            val dy = Math.sin(c.angle.toDouble()).toFloat() * c.length
            // Fade head→tail by drawing a gradient line as several
            // shorter strokes with decreasing alpha.
            for (i in 0..6) {
                val f = i / 6f
                cometPaint.alpha = ((1f - f) * 230f).toInt()
                canvas.drawLine(
                    cx - dx * f, cy - dy * f,
                    cx - dx * (f + 0.15f), cy - dy * (f + 0.15f),
                    cometPaint,
                )
            }
        }
    }
}
