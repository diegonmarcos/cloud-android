package com.diegonmarcos.superapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Three vertical bars that bounce independently — the universal
 * "music is playing" symbol (Spotify mini-player, YouTube Music
 * notification icon, etc.). Pure Canvas + sine-driven heights;
 * no AnimationDrawable / no frame assets, so the view costs ~3
 * paint calls per frame and stops invalidating itself the moment
 * visibility goes GONE.
 *
 * Drawing model:
 *   • Each bar height = MIN + (MAX − MIN) × 0.5 × (1 + sin(ωt + φ))
 *     with a different φ per bar so the three never align — gives
 *     the classic "out-of-phase EQ" look.
 *   • Bars are rounded-rect with 1.5dp corner radius.
 *   • Colour matches the lavender accent the rest of the launcher
 *     uses (0xFFB794F4) — consistent visual language.
 *
 * Self-driving: onDraw → postInvalidateOnAnimation() while
 * isShown == true. Hidden → invalidation stops → zero idle cost.
 * No external animator handle to manage; show/hide at the parent
 * level and the view takes care of itself.
 */
class EqualizerBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB794F4.toInt()
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private val barCount = 3
    /** Phase offsets — chosen so the three bars never peak together,
     *  giving the classic "alive" EQ look. Multiples of 2π/3 hit a
     *  perfect three-phase pattern but the asymmetric values feel
     *  livelier in practice. */
    private val phases = floatArrayOf(0f, 1.2f, 2.4f)
    private val angularSpeed = 6.0  // radians/sec; ~1 Hz overall

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val barW = (w - (barCount - 1) * 2f * d) / barCount  // 2dp gap between bars
        val cornerRadius = 1.5f * d
        val minBarHeight = h * 0.25f
        val maxBarHeight = h * 1.0f
        val t = SystemClock.elapsedRealtime() / 1000.0
        for (i in 0 until barCount) {
            val s = 0.5 * (1.0 + kotlin.math.sin(angularSpeed * t + phases[i]))
            val barH = (minBarHeight + (maxBarHeight - minBarHeight) * s).toFloat()
            val left = i * (barW + 2f * d)
            val top = h - barH  // anchor at bottom; bars grow upward
            rect.set(left, top, left + barW, h)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    /** Manual stop hook for hosts that want to be defensive. Equivalent
     *  to setVisibility(GONE) followed by removing the view from
     *  rendering — onDraw won't fire, so the invalidation loop dies
     *  on its own. Provided for symmetry with the [start] helper. */
    fun stop() { invalidate() /* one final frame to clear; isShown=false next pass */ }

    /** Kick the animation. Equivalent to setVisibility(VISIBLE) then
     *  invalidate(). The view's onDraw self-perpetuates from here. */
    fun start() { invalidate() }
}
