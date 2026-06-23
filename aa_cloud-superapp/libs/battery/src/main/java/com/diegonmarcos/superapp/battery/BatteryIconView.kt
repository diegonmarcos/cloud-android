package com.diegonmarcos.superapp.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Samsung-One-UI-style horizontal battery icon — outlined body + small
 * terminal nub on the right + a coloured fill from the left whose width
 * tracks the charge percentage. The numeric percentage is drawn INSIDE
 * the body (white text + black shadow halo, so it survives the
 * coloured fill on the left and the dark backdrop on the right).
 *
 * Fill colour ramp:
 *   charging  → green
 *   ≤ 15 %    → red
 *   ≤ 30 %    → amber
 *   otherwise → white (translucent so the galaxy bleeds through)
 *
 * Use [setBattery] with current level + charging flag; the view
 * invalidates itself.
 */
class BatteryIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }
    private val rect = RectF()
    private val nub = RectF()
    private val fill = RectF()

    private var levelPct: Int = -1
    private var charging: Boolean = false

    fun setBattery(level: Int, isCharging: Boolean) {
        if (level == levelPct && isCharging == charging) return
        levelPct = level
        charging = isCharging
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        // Intrinsic size — readable at the status-bar scale on both
        // typical (3 ppdp) and high-density (3.5+) phones.
        val w = (36 * d).toInt()
        val h = (15 * d).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val stroke = max(1f * d, 1f)
        val nubWidth = 2f * d
        val nubHeight = h * 0.45f
        val bodyRight = w - nubWidth - stroke

        // Body outline (rounded)
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        rect.set(stroke / 2f, stroke / 2f, bodyRight, h - stroke / 2f)
        val outerRadius = h * 0.22f
        canvas.drawRoundRect(rect, outerRadius, outerRadius, paint)

        // Nub on the right edge
        paint.style = Paint.Style.FILL
        nub.set(bodyRight, (h - nubHeight) / 2f, w, (h + nubHeight) / 2f)
        canvas.drawRoundRect(nub, d, d, paint)

        // Coloured fill — proportional to level
        if (levelPct >= 0) {
            val pct = (levelPct.coerceIn(0, 100)) / 100f
            val fillInset = stroke + 1f * d
            val fillLeft = fillInset
            val fillTop = fillInset
            val fillRight = fillInset + ((bodyRight - 2f * fillInset) * pct)
            val fillBottom = h - fillInset
            paint.color = when {
                charging     -> 0xFF22C55E.toInt()  // green
                levelPct <= 15 -> 0xFFEF4444.toInt() // red
                levelPct <= 30 -> 0xFFF59E0B.toInt() // amber
                else         -> 0xCCFFFFFF.toInt()   // translucent white
            }
            fill.set(fillLeft, fillTop, fillRight, fillBottom)
            val innerRadius = outerRadius * 0.6f
            canvas.drawRoundRect(fill, innerRadius, innerRadius, paint)
        }

        // Percentage text centred over the body — white with black
        // shadow halo so it stays legible on both filled and unfilled
        // halves.
        val text = if (levelPct >= 0) "$levelPct" else "—"
        textPaint.textSize = h * 0.62f
        textPaint.setShadowLayer(2f * d, 0f, 0f, 0xCC000000.toInt())
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, bodyRight / 2f, textY, textPaint)
    }
}
