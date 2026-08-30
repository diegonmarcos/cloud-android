package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A Google-Maps-style scale bar: a short bar + "500 m" / "2 km" label that
 * appears while the zoom is changing and fades out shortly after the camera
 * settles. MapLibre Native ships no scale bar in its UiSettings and its scalebar
 * plugin is always-on, so this is a tiny custom view driven from
 * `Projection.getMetersPerPixelAtLatitude`.
 *
 * [niceScale]/[label] are pure and unit-tested (the only non-trivial logic —
 * choosing a round 1/2/5×10ⁿ distance that fits the max width). Colours/sizes
 * are UI chrome constants, not data.
 */
class MapsScaleBarView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val maxWidthPx = dp(96f)
    private val padH = dp(7f)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = dp(2f); style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = dp(11f); textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88101418.toInt() }

    private var barPx = 0f
    private var text = ""
    private var pendingHide: Runnable? = null

    init { alpha = 0f }

    /** Recompute the bar for the current scale (does not change visibility). */
    fun update(metersPerPixel: Double) {
        val (meters, px) = niceScale(metersPerPixel, maxWidthPx.toDouble())
        if (meters <= 0.0) return
        barPx = px.toFloat()
        text = label(meters)
        requestLayout()
        invalidate()
    }

    /** Show immediately (zoom changed), cancelling any pending fade-out. */
    fun showNow() {
        pendingHide?.let { removeCallbacks(it) }; pendingHide = null
        animate().cancel()
        alpha = 1f
    }

    /** Fade out after [delayMs] of no scale change (camera idle). */
    fun scheduleHide(delayMs: Long = 900L) {
        pendingHide?.let { removeCallbacks(it) }
        val r = Runnable { animate().alpha(0f).setDuration(400L).start() }
        pendingHide = r
        postDelayed(r, delayMs)
    }

    override fun onDetachedFromWindow() {
        pendingHide?.let { removeCallbacks(it) }; pendingHide = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((padH * 2 + barPx).toInt().coerceAtLeast(1), dp(30f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(4f), dp(4f), bgPaint)
        val barY = h - dp(9f)
        val left = padH
        val right = padH + barPx
        canvas.drawLine(left, barY, right, barY, barPaint)              // bar
        canvas.drawLine(left, barY, left, barY - dp(6f), barPaint)      // left tick
        canvas.drawLine(right, barY, right, barY - dp(6f), barPaint)    // right tick
        canvas.drawText(text, (left + right) / 2f, barY - dp(8f), textPaint)
    }

    companion object {
        /** Largest round 1/2/5×10ⁿ metre distance whose on-screen width fits
         *  [maxWidthPx]; returns (metres, pixelWidth). (0,0) for bad input. */
        fun niceScale(metersPerPixel: Double, maxWidthPx: Double): Pair<Double, Double> {
            if (metersPerPixel <= 0.0 || maxWidthPx <= 0.0) return 0.0 to 0.0
            val maxMeters = metersPerPixel * maxWidthPx
            val base = 10.0.pow(floor(log10(maxMeters)))
            val meters = listOf(5.0, 2.0, 1.0).map { it * base }.firstOrNull { it <= maxMeters } ?: base
            return meters to (meters / metersPerPixel)
        }

        /** "500 m" / "2 km" — inputs are always round 1/2/5×10ⁿ. */
        fun label(meters: Double): String = when {
            meters >= 1000.0 -> {
                val km = meters / 1000.0
                "${if (km == floor(km)) km.toInt().toString() else km.toString()} km"
            }
            meters >= 1.0 -> "${meters.toInt()} m"
            else -> "${"%.1f".format(meters)} m"
        }
    }
}
