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
 * Animated gesture preview (One Hand Operation+ style). On touch-down it fans out
 * the handle's direction options as pills; the arrow follows the finger and the
 * active option (top / middle / bottom) is highlighted, the highlight easing
 * smoothly as you tilt between sectors. Purely visual; fed by the a11y service.
 */
class GesturePreviewView(ctx: Context) : View(ctx) {

    data class Option(
        val key: String, val label: String, val angleDeg: Double,
        val icon: android.graphics.Bitmap? = null,
    ) {
        var hi = 0f // 0..1 highlight, eased toward active
    }

    private val d = resources.displayMetrics.density
    private var active = false
    private var sx = 0f; private var sy = 0f
    private var cx = 0f; private var cy = 0f
    private var activeKey: String? = null
    private var options: List<Option> = emptyList()
    private var lastFrameNs = 0L

    private val accent = Color.parseColor("#4DA3FF")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 7f * d
        setShadowLayer(5f * d, 0f, 0f, Color.BLACK)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0.016f else (now - lastFrameNs) / 1e9f
            lastFrameNs = now
            val k = (dt * 14f).coerceIn(0f, 1f) // exponential ease
            options.forEach { o -> o.hi += ((if (o.key == activeKey) 1f else 0f) - o.hi) * k }
            invalidate()
            if (active) postOnAnimation(this)
        }
    }

    fun begin(opts: List<Option>) {
        options = opts; active = true; activeKey = null; lastFrameNs = 0L
        removeCallbacks(frame); postOnAnimation(frame)
    }

    fun update(startX: Float, startY: Float, curX: Float, curY: Float, key: String?) {
        sx = startX; sy = startY; cx = curX; cy = curY; activeKey = key
    }

    fun end() { active = false; removeCallbacks(frame); invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val r = 104f * d
        // Option pills fanned around the swipe origin.
        for (o in options) {
            val a = Math.toRadians(o.angleDeg)
            val px = sx + (r * cos(a)).toFloat()
            val py = sy + (r * sin(a)).toFloat()
            val hi = o.hi
            fill.color = blend(Color.argb(160, 30, 30, 34), accent, hi)
            if (o.icon != null) {
                // App: draw the launcher icon in a rounded chip that grows when active.
                val half = (18f + 5f * hi) * d
                val chip = RectF(px - half, py - half, px + half, py + half)
                canvas.drawRoundRect(chip, 14f * d, 14f * d, fill)
                val ic = (14f + 4f * hi) * d
                canvas.drawBitmap(o.icon, null, RectF(px - ic, py - ic, px + ic, py + ic), null)
            } else {
                val padH = (12f + 4f * hi) * d
                val padV = (8f + 3f * hi) * d
                val w = text.measureText(o.label) / 2f
                val rect = RectF(px - w - padH, py - padV - 7f * d, px + w + padH, py + padV + 7f * d)
                canvas.drawRoundRect(rect, 14f * d, 14f * d, fill)
                text.color = blend(Color.LTGRAY, Color.WHITE, hi)
                canvas.drawText(o.label, px, py + 5f * d, text)
            }
        }
        // Arrow following the finger.
        val len = hypot(cx - sx, cy - sy)
        if (len >= 8f * d) {
            line.color = accent
            canvas.drawLine(sx, sy, cx, cy, line)
            val ang = atan2((cy - sy).toDouble(), (cx - sx).toDouble())
            val head = 20f * d
            for (off in listOf(Math.toRadians(150.0), Math.toRadians(-150.0))) {
                canvas.drawLine(cx, cy,
                    cx + (head * cos(ang + off)).toFloat(),
                    cy + (head * sin(ang + off)).toFloat(), line)
            }
            canvas.drawCircle(cx, cy, 6f * d, dot)
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun c(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return Color.argb(
            c(Color.alpha(from), Color.alpha(to)), c(Color.red(from), Color.red(to)),
            c(Color.green(from), Color.green(to)), c(Color.blue(from), Color.blue(to)),
        )
    }
}
