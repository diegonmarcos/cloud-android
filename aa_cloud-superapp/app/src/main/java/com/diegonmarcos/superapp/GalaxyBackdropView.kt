package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural galaxy backdrop —
 *   • 220 twinkling stars (slow alpha sin-modulation)
 *   • 6 "blowing" supernova stars (periodic bright flare + radial rays)
 *   • 3 cross-screen comets (long bright tails, full-diagonal travel,
 *     each on its own phase)
 * Drawn behind the wireframe cube. Pure Canvas, seeded for stable
 * star layout across launches.
 */
class GalaxyBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
    private data class Nova(val x: Float, val y: Float, val phase: Float, val period: Float)
    private data class Comet(
        val phase: Float,
        val sx: Float, val sy: Float,   // start [0..1]
        val ex: Float, val ey: Float,   // end [0..1]
        val travel: Float,              // fraction of period spent traveling (0..1)
    )

    private val rng = Random(20260531L)
    private val stars = (0..220).map {
        Star(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            r = rng.nextFloat() * 2.2f + 0.4f,
            phase = rng.nextFloat() * (Math.PI * 2f).toFloat(),
        )
    }
    private val novas = (0..5).map {
        Nova(
            x = rng.nextFloat(),
            y = rng.nextFloat(),
            phase = rng.nextFloat(),
            period = 0.18f + rng.nextFloat() * 0.14f,
        )
    }
    private val comets = listOf(
        // Each comet travels DIAGONALLY across the full screen.
        Comet(phase = 0.00f, sx = -0.15f, sy = -0.10f, ex = 1.20f, ey = 0.95f, travel = 0.35f),
        Comet(phase = 0.42f, sx =  1.15f, sy =  0.05f, ex = -0.20f, ey = 1.05f, travel = 0.40f),
        Comet(phase = 0.78f, sx = -0.15f, sy =  0.85f, ex = 1.10f, ey =-0.10f, travel = 0.32f),
    )

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9D8FD.toInt()
    }
    private val novaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val novaRay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cometCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f
        color = 0xFFFFFFFF.toInt()
    }
    private val cometTail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFB794F4.toInt()
    }

    private var time = 0f

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 18_000      // full period — comets time their phases inside this
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

        // ── twinkling stars ────────────────────────────────────────
        for (s in stars) {
            val twinkle = (sin(time * 12f + s.phase) * 0.4f + 0.6f).absoluteValue
            starPaint.alpha = (twinkle * 220f).toInt().coerceIn(40, 255)
            canvas.drawCircle(s.x * w, s.y * h, s.r, starPaint)
        }

        // ── blowing supernova stars ────────────────────────────────
        for (n in novas) {
            val t = ((time + n.phase) % 1f) / n.period
            // active during the first stretch of its period
            if (t > 1f) continue
            val eased = (sin(t * Math.PI.toFloat()) * 1f)        // 0→1→0 bell
            val cx = n.x * w; val cy = n.y * h
            val coreR = 2.5f + eased * 8f
            val haloR = 6f + eased * 28f
            // halo: soft violet
            novaPaint.color = 0xFFB794F4.toInt()
            novaPaint.alpha = (eased * 120f).toInt()
            canvas.drawCircle(cx, cy, haloR, novaPaint)
            // bright core
            novaPaint.color = 0xFFFFFFFF.toInt()
            novaPaint.alpha = (eased * 240f).toInt().coerceIn(60, 255)
            canvas.drawCircle(cx, cy, coreR, novaPaint)
            // radial rays
            if (eased > 0.4f) {
                novaRay.color = 0xFFE9D8FD.toInt()
                novaRay.alpha = ((eased - 0.4f) * 200f).toInt().coerceIn(0, 255)
                novaRay.strokeWidth = 1.5f + eased * 1.5f
                val rayLen = 8f + eased * 22f
                for (k in 0..5) {
                    val ang = (k.toFloat() / 6f) * Math.PI.toFloat() * 2f
                    canvas.drawLine(
                        cx + cos(ang) * coreR * 1.4f,
                        cy + sin(ang) * coreR * 1.4f,
                        cx + cos(ang) * (coreR + rayLen),
                        cy + sin(ang) * (coreR + rayLen),
                        novaRay,
                    )
                }
            }
        }

        // ── full-diagonal comets ───────────────────────────────────
        for (c in comets) {
            val cycle = ((time + c.phase) % 1f)
            if (cycle > c.travel) continue
            val prog = cycle / c.travel                          // 0..1 across the screen
            val headX = (c.sx + (c.ex - c.sx) * prog) * w
            val headY = (c.sy + (c.ey - c.sy) * prog) * h
            val dirX = (c.ex - c.sx) * w
            val dirY = (c.ey - c.sy) * h
            val len  = Math.sqrt((dirX * dirX + dirY * dirY).toDouble()).toFloat()
            val tailLen = 220f                                    // long pixel tail
            val ux = dirX / len; val uy = dirY / len
            // Tail = many short stroke segments, each fainter than the last.
            val seg = 12
            for (i in 0 until seg) {
                val t0 = i.toFloat() / seg
                val t1 = (i + 1f) / seg
                val a = ((1f - t0) * 230f).toInt().coerceIn(0, 255)
                cometTail.alpha = a
                cometTail.strokeWidth = 3.5f - (t0 * 3f)
                canvas.drawLine(
                    headX - ux * tailLen * t0,
                    headY - uy * tailLen * t0,
                    headX - ux * tailLen * t1,
                    headY - uy * tailLen * t1,
                    cometTail,
                )
            }
            // Bright head dot.
            canvas.drawLine(
                headX - ux * 4f, headY - uy * 4f,
                headX + ux * 4f, headY + uy * 4f,
                cometCore,
            )
        }
    }
}
