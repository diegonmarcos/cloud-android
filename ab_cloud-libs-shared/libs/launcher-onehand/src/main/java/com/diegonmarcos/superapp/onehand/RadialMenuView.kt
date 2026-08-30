package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Two-finger radial menu (visual only — all touch tracking is observe-only via
 * the accessibility service's onMotionEvent, so this window is NOT touchable and
 * never steals input). Icons are laid in a ring around the invocation point; an
 * arrow follows the finger and the nearest item highlights, easing its scale.
 */
class RadialMenuView(ctx: Context) : View(ctx) {

    class Item(val label: String, val icon: android.graphics.Bitmap?) {
        var x = 0f; var y = 0f; var hi = 0f
    }

    private val d = resources.displayMetrics.density
    private var active = false
    private var cx = 0f; private var cy = 0f
    private var fx = 0f; private var fy = 0f
    private var items: List<Item> = emptyList()
    private var activeIndex = -1
    private var lastFrameNs = 0L

    private val accent = Color.parseColor("#4DA3FF")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 7f * d
        color = accent; setShadowLayer(5f * d, 0f, 0f, Color.BLACK)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * d; textAlign = Paint.Align.CENTER
        isFakeBoldText = true; setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0.016f else (now - lastFrameNs) / 1e9f
            lastFrameNs = now
            val k = (dt * 14f).coerceIn(0f, 1f)
            items.forEachIndexed { i, it -> it.hi += ((if (i == activeIndex) 1f else 0f) - it.hi) * k }
            invalidate()
            if (active) postOnAnimation(this)
        }
    }

    /** Open at (centerX, centerY) with items placed on a ring of `radiusPx`. */
    fun open(centerX: Float, centerY: Float, radiusPx: Float, its: List<Item>) {
        cx = centerX; cy = centerY; fx = centerX; fy = centerY
        items = its; activeIndex = -1; active = true; lastFrameNs = 0L
        val n = its.size.coerceAtLeast(1)
        its.forEachIndexed { i, it ->
            val ang = -Math.PI / 2 + 2 * Math.PI * i / n // start at top, clockwise
            it.x = cx + (radiusPx * cos(ang)).toFloat()
            it.y = cy + (radiusPx * sin(ang)).toFloat()
        }
        removeCallbacks(frame); postOnAnimation(frame)
    }

    /** Move the pointer; returns the index of the highlighted item (or -1). */
    fun move(px: Float, py: Float, hitRadiusPx: Float): Int {
        fx = px; fy = py
        // Highlight the item nearest the finger, if the finger is far enough from
        // center to count as a choice and within hit range of that item.
        activeIndex = if (hypot(px - cx, py - cy) < 24f * d) -1 else
            items.indices.minByOrNull { hypot(px - items[it].x, py - items[it].y) }
                ?.takeIf { hypot(px - items[it].x, py - items[it].y) <= hitRadiusPx } ?: -1
        return activeIndex
    }

    fun close() { active = false; removeCallbacks(frame); invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        if (hypot(fx - cx, fy - cy) > 8f * d) canvas.drawLine(cx, cy, fx, fy, line)
        canvas.drawCircle(cx, cy, 6f * d, dot)
        items.forEachIndexed { i, it ->
            val hi = it.hi
            fill.color = if (i == activeIndex)
                Color.argb(230, 77, 163, 255) else Color.argb(170, 30, 30, 34)
            val half = (24f + 8f * hi) * d
            canvas.drawRoundRect(RectF(it.x - half, it.y - half, it.x + half, it.y + half),
                16f * d, 16f * d, fill)
            if (it.icon != null) {
                val ic = (18f + 5f * hi) * d
                canvas.drawBitmap(it.icon, null, RectF(it.x - ic, it.y - ic, it.x + ic, it.y + ic), null)
            } else {
                canvas.drawText(it.label.take(6), it.x, it.y + 5f * d, text)
            }
        }
    }
}
