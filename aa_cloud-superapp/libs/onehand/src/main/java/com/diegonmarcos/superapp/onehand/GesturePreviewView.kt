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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Two-level morphing arc menu drawn over the edge handle swipe.
 *
 * L1 (progress=0): tight arc centred on the swipe origin (the handle), radius ~130dp.
 *   Items fan out inward — for a left handle they go right, for a right handle left.
 *
 * L2 (progress=1): arc whose centre is far below the screen (~3× screen height).
 *   The resulting arc is nearly a straight horizontal line distributing items
 *   left→right across the full screen width.
 *
 * The positions, radius, and item sizes all interpolate smoothly between L1 and L2
 * as the finger is dragged further inward.
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
    private var progress = 0f   // 0=L1 tight arc, 1=L2 near-horizontal

    // ── display ────────────────────────────────────────────────────────────────
    private val dm = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getMetrics(it)
    }
    private val dp = dm.density

    // ── paints ─────────────────────────────────────────────────────────────────
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 30, 30, 50)
    }
    private val pillSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 60, 120, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD; textSize = 13 * dp
        setShadowLayer(4 * dp, 0f, 2 * dp, Color.argb(160, 0, 0, 0))
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255); strokeWidth = 3 * dp
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private val frame = object : Runnable {
        override fun run() { if (active) { invalidate(); postDelayed(this, 16) } }
    }

    // ── public API ─────────────────────────────────────────────────────────────

    fun begin(opts: List<Option>, edge: OneHandConfig.Edge) {
        this.opts = opts; this.edge = edge; active = true; selKey = null; progress = 0f
        postDelayed(frame, 0)
    }

    fun update(startX: Float, startY: Float, curX: Float, curY: Float, key: String?, progress: Float) {
        this.startX = startX; this.startY = startY
        this.curX   = curX;   this.curY   = curY
        this.selKey = key;    this.progress = progress
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

        // ── L2 geometry: arc centre far below screen → near-horizontal line ──
        // Items target row: 60% down the screen (visible, lower-middle area)
        // Centre is placed at (targetY + r2) so the arc's peak lands exactly at targetY.
        // Spread is computed so outermost items sit at ±44% of screen width from centre.
        val r2 = sh * 3f
        val l2TargetY = sh * 0.60f
        val l2CentreX = sw * 0.5f
        val l2CentreY = l2TargetY + r2
        val l2BaseAngle = -PI / 2
        val l2Spread = asin((sw * 0.44).toDouble() / r2)   // half-angle; total span ≈ 88% sw

        // ── interpolate per-item positions ──
        val t = progress.toDouble()

        val pillW = lerp(90 * dp, 100 * dp, t); val pillH = lerp(36 * dp, 44 * dp, t)
        val iconSz = lerp(22 * dp, 28 * dp, t)

        for ((idx, opt) in opts.withIndex()) {
            // angle within the fan/arc
            val frac = if (n <= 1) 0.0 else (idx.toDouble() / (n - 1)) - 0.5  // -0.5..+0.5
            val a1 = l1Centre + frac * l1Spread * 2
            val a2 = l2BaseAngle + frac * l2Spread * 2

            // L1 item centre
            val x1 = (l1Cx + r1 * cos(a1)).toFloat()
            val y1 = (l1Cy + r1 * sin(a1)).toFloat()

            // L2 item centre
            val x2 = (l2CentreX + r2 * cos(a2)).toFloat()
            val y2 = (l2CentreY + r2 * sin(a2)).toFloat()

            // Interpolated position
            val ix = lerp(x1, x2, t); val iy = lerp(y1, y2, t)

            val selected = opt.key == selKey
            val rect = RectF(ix - pillW / 2, iy - pillH / 2, ix + pillW / 2, iy + pillH / 2)
            canvas.drawRoundRect(rect, pillH / 2, pillH / 2, if (selected) pillSelPaint else pillPaint)

            val topY = iy - pillH / 2
            if (opt.icon != null) {
                val iconRect = RectF(ix - iconSz / 2, topY + (pillH - iconSz) / 2,
                                     ix + iconSz / 2, topY + (pillH + iconSz) / 2)
                canvas.drawBitmap(opt.icon, null, iconRect, null)
            } else {
                textPaint.textSize = lerp(13 * dp, 14 * dp, t)
                canvas.drawText(opt.label, ix, iy + textPaint.textSize * 0.38f, textPaint)
            }
        }

        // ── follow-finger arrow (fades out as L2 opens) ──────────────────────
        if (progress < 0.8f) {
            val alpha = ((1f - progress / 0.8f) * 180).toInt()
            arrowPaint.alpha = alpha
            canvas.drawLine(startX, startY, curX, curY, arrowPaint)
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private fun lerp(a: Float, b: Float, t: Double) = (a + (b - a) * t).toFloat()
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}
