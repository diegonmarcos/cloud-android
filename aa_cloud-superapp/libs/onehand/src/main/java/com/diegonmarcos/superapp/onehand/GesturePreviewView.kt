package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * EDGE-MENU overlay — NOT the Canopus arc circle menu.
 *
 * Draws the three action items in a fixed arc centred on the swipe origin
 * (the handle position). Items fan inward: right-handle fans left, left-handle
 * fans right, bottom-handle fans up.
 *
 * Multi-level / sub-arc behaviour belongs to the Canopus circle (RadialMenuView).
 * This view has no L2 / morphing / transition logic.
 */
class GesturePreviewView(ctx: Context) : View(ctx) {

    data class Option(val key: String, val label: String, val icon: Bitmap?)

    // ── state ──────────────────────────────────────────────────────────────────
    private var active  = false
    private var opts    = listOf<Option>()
    private var edge    = OneHandConfig.Edge.RIGHT
    private var startX  = 0f; private var startY  = 0f
    private var curX    = 0f; private var curY    = 0f
    private var selKey  : String? = null
    private var swipeDist = 0f  // normalised distance from threshold — used for arrow fade

    // ── display ────────────────────────────────────────────────────────────────
    private val dm = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getMetrics(it)
    }
    private val dp = dm.density

    // ── paints ─────────────────────────────────────────────────────────────────
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD; textSize = 13 * dp
        setShadowLayer(4 * dp, 0f, 2 * dp, Color.argb(160, 0, 0, 0))
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255); strokeWidth = 3 * dp
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val selCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 60, 120, 255); style = Paint.Style.FILL
    }
    private val selCircleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 100, 160, 255); style = Paint.Style.STROKE; strokeWidth = 2 * dp
    }

    private val frame = object : Runnable {
        override fun run() { if (active) { invalidate(); postDelayed(this, 16) } }
    }

    // ── public API ─────────────────────────────────────────────────────────────

    fun begin(opts: List<Option>, edge: OneHandConfig.Edge) {
        this.opts = opts; this.edge = edge; active = true; selKey = null; swipeDist = 0f
        postDelayed(frame, 0)
    }

    fun update(startX: Float, startY: Float, curX: Float, curY: Float, key: String?, progress: Float) {
        this.startX = startX; this.startY = startY
        this.curX   = curX;   this.curY   = curY
        this.selKey = key;    this.swipeDist = progress
    }

    fun end() { active = false; removeCallbacks(frame); invalidate() }

    // ── drawing ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (!active || opts.isEmpty()) return

        val sw = width.toFloat(); val sh = height.toFloat()
        val n  = opts.size

        // ── L1 geometry: tight arc centred at swipe start ──
        val r1 = 130 * dp
        // Fan angles (radians). For LEFT handle items fan right (0°), for RIGHT left (180°).
        val l1Centre = when (edge) {
            OneHandConfig.Edge.LEFT   -> 0.0        // pointing right
            OneHandConfig.Edge.RIGHT  -> PI          // pointing left
            OneHandConfig.Edge.BOTTOM -> -PI / 2    // pointing up
        }
        val l1Spread = PI / 4  // ±45° total, so 3 items at -45°, 0°, +45°
        val l1Cx = startX; val l1Cy = startY

        // Edge menu: items stay at L1 arc. No L2 morphing (L2 levels belong to the Canopus circle).
        val iconSz = 22 * dp

        for ((idx, opt) in opts.withIndex()) {
            val frac = if (n <= 1) 0.0 else (idx.toDouble() / (n - 1)) - 0.5  // -0.5..+0.5
            val a1 = l1Centre + frac * l1Spread * 2
            val ix = (l1Cx + r1 * cos(a1)).toFloat()
            val iy = (l1Cy + r1 * sin(a1)).toFloat()

            val selected = opt.key == selKey
            val circleR = iconSz * 0.8f
            if (selected) {
                canvas.drawCircle(ix, iy, circleR, selCirclePaint)
                canvas.drawCircle(ix, iy, circleR, selCircleStroke)
            }
            if (opt.icon != null) {
                val iconRect = RectF(ix - iconSz / 2, iy - iconSz / 2,
                                     ix + iconSz / 2, iy + iconSz / 2)
                canvas.drawBitmap(opt.icon, null, iconRect, null)
            } else {
                textPaint.textSize = 13 * dp
                textPaint.alpha = if (selected) 255 else 200
                canvas.drawText(opt.label, ix, iy + textPaint.textSize * 0.38f, textPaint)
            }
        }

        // ── follow-finger arrow with arrowhead (fades out past swipeDist=1) ──
        val arrowAlpha = ((1f - (swipeDist - 1f).coerceAtLeast(0f)) * 180).toInt().coerceIn(0, 180)
        arrowPaint.alpha = arrowAlpha
        canvas.drawLine(startX, startY, curX, curY, arrowPaint)
        // arrowhead: two lines angled 140° from the shaft direction
        val dx = curX - startX; val dy = curY - startY
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / len; val uy = dy / len
        val headLen = 18 * dp
        val angle = Math.toRadians(140.0)
        val cos = Math.cos(angle).toFloat(); val sin = Math.sin(angle).toFloat()
        canvas.drawLine(curX, curY, curX + headLen * (ux * cos - uy * sin), curY + headLen * (ux * sin + uy * cos), arrowPaint)
        canvas.drawLine(curX, curY, curX + headLen * (ux * cos + uy * sin), curY + headLen * (-(ux * sin) + uy * cos), arrowPaint)
    }

}
