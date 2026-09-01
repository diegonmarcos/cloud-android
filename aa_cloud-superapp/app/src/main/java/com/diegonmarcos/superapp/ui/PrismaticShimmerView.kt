package com.diegonmarcos.superapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Full-spectrum rainbow "prismatic" shimmer around a rounded-pill
 * perimeter. Like [ShimmerBorderView] but the SweepGradient covers the
 * whole hue wheel — all 7 colours of the spectrum chase around the
 * border at once, so the effect reads as a neon-prism halo rather than
 * a single violet comet. Used around the Dynamic Island in the
 * activity toolbar.
 *
 * Auto-corners to a pill (radius = height/2) since the island is itself
 * a pill — override [cornerRadiusDp] to override.
 */
class PrismaticShimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private var progress = 0f

    /** Per-frame rotation step. When negative the view auto-derives a
     *  pill radius from its height. */
    var cornerRadiusDp: Float = -1f

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sw = paint.strokeWidth
        val rect = RectF(sw, sw, width - sw, height - sw)
        val cx = rect.centerX()
        val cy = rect.centerY()

        // Full rainbow sweep. Looping back to red at the end keeps the
        // gradient seamless under any rotation phase.
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFFFF1744.toInt(), // red
                0xFFFF9100.toInt(), // orange
                0xFFFFEA00.toInt(), // yellow
                0xFF00E676.toInt(), // green
                0xFF00E5FF.toInt(), // cyan
                0xFF2979FF.toInt(), // blue
                0xFFAA00FF.toInt(), // violet
                0xFFFF1744.toInt(), // back to red — closes the loop
            ),
            null,
        )
        val m = Matrix().apply { postRotate(progress * 360f, cx, cy) }
        sweep.setLocalMatrix(m)
        paint.shader = sweep

        val r = if (cornerRadiusDp < 0f) rect.height() / 2f
                else cornerRadiusDp * resources.displayMetrics.density
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
