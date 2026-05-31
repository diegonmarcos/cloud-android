package com.diegonmarcos.superapp

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
 * Continuous "neon comet" shimmer around a rounded rectangle perimeter.
 * Paints a SweepGradient (transparent → bright violet → transparent)
 * centred on the view, rotates the gradient via a local matrix on each
 * frame, and clips it to a rounded-rect stroke. Effect: a glowing
 * highlight chases around the border.
 *
 * Stack this on top of [toolbar_island] inside its FrameLayout host;
 * sized MATCH_PARENT so it covers the same bounds.
 */
class ShimmerBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private var progress = 0f

    var cornerRadiusDp: Float = 22f

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
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

        // Transparent → bright violet hot-spot → transparent, twice round
        // so both ends meet seamlessly when the matrix rotates.
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(
                0x00000000,
                0x00B794F4.toInt(),
                0xFFE9D8FD.toInt(),
                0x00B794F4.toInt(),
                0x00000000,
            ),
            floatArrayOf(0f, 0.45f, 0.5f, 0.55f, 1f),
        )
        val m = Matrix().apply { postRotate(progress * 360f, cx, cy) }
        sweep.setLocalMatrix(m)
        paint.shader = sweep

        val r = cornerRadiusDp * resources.displayMetrics.density
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
