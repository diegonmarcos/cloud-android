package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Full-screen, non-touchable overlay that draws an arrow from the swipe origin
 * toward the finger (so it follows the drag, tilting up/down as One Hand
 * Operation+ does) plus the label of the action the current sector will fire.
 * Purely visual feedback; the accessibility service feeds it screen coords.
 */
class GesturePreviewView(ctx: Context) : View(ctx) {

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // shadow layer needs software render

    private var active = false
    private var sx = 0f; private var sy = 0f
    private var cx = 0f; private var cy = 0f
    private var label: String = ""

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 6f * d; strokeCap = Paint.Cap.ROUND
        setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f * d; textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
    }

    fun update(startX: Float, startY: Float, curX: Float, curY: Float, actionLabel: String) {
        active = true; sx = startX; sy = startY; cx = curX; cy = curY; label = actionLabel
        invalidate()
    }

    fun clearPreview() { active = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val len = hypot(cx - sx, cy - sy)
        if (len < 8f * d) { canvas.drawText(label, cx, cy - 56f * d, text); return }
        canvas.drawLine(sx, sy, cx, cy, line)
        // arrowhead
        val ang = atan2((cy - sy).toDouble(), (cx - sx).toDouble())
        val head = 18f * d
        for (off in listOf(Math.toRadians(150.0), Math.toRadians(-150.0))) {
            canvas.drawLine(
                cx, cy,
                cx + (head * cos(ang + off)).toFloat(),
                cy + (head * sin(ang + off)).toFloat(), line,
            )
        }
        // Label sits AHEAD of the fingertip along the drag direction, offset off
        // the axis, so the finger never covers it.
        if (label.isNotEmpty()) {
            val ext = 64f * d
            val lx = cx + (ext * cos(ang)).toFloat()
            val ly = cy + (ext * sin(ang)).toFloat() - 12f * d
            canvas.drawText(label, lx, ly, text)
        }
    }
}
