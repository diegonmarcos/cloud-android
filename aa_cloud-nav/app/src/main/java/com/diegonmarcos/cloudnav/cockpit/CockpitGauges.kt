package com.diegonmarcos.cloudnav.cockpit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Professional-grade cockpit instruments, drawn on Canvas (no XML, no assets) so
 * they scale to any panel slot. Each is a self-contained [View] with a single
 * setter the [com.diegonmarcos.cloudnav.routes.NavigationFragment] feeds from GPS
 * fixes + [CockpitSensors].
 *
 * Factory: [CockpitGauges.create] maps a build.json gauge id → the right widget
 * (FIRE rule 6 — the id list is data, this is just the render registry).
 */
/**
 * A live instrument the fragment can feed. The concrete Canvas views are private;
 * the fragment only sees this interface (calling the setters relevant to the gauge
 * id — the rest are no-ops).
 */
interface CockpitInstrument {
    val view: View
    fun onSpeed(v: Double, unit: String) {}
    fun onHeading(deg: Float) {}
    fun onAttitude(pitch: Float, roll: Float) {}
    fun onAltitude(v: Double) {}
    fun onVerticalMps(v: Double) {}
    fun onMetric(value: String, second: String? = null) {}
}

object CockpitGauges {

    const val BG = 0xFF10141C.toInt()
    const val SUB = 0xFFA9B0BD.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()

    /** Build the instrument for [id]. Unknown ids → a labelled placeholder MetricView. */
    fun create(ctx: Context, id: String, mode: com.diegonmarcos.cloudnav.CockpitMode): CockpitInstrument {
        val accent = mode.accent
        return when (id) {
            "speed"    -> SpeedGauge(ctx, accent, speedMax(mode))
            "heading"  -> CompassRose(ctx, accent)
            "attitude" -> AttitudeIndicator(ctx, accent)
            "altitude" -> AltitudeTape(ctx, accent, mode.altUnit)
            "vspeed"   -> VSpeedGauge(ctx, accent, mode.altUnit)
            "grade"    -> MetricView(ctx, accent, "GRADE")
            "avg"      -> MetricView(ctx, accent, "AVG")
            "dist"     -> MetricView(ctx, accent, "DIST")
            "eta"      -> MetricView(ctx, accent, "ETA")
            "position" -> MetricView(ctx, accent, "POSITION")
            "track"    -> MetricView(ctx, accent, "TRACK")
            else       -> MetricView(ctx, accent, id.uppercase())
        }
    }

    /** Full-scale of the speed dial, per unit/vehicle (derived — not a magic literal). */
    private fun speedMax(mode: com.diegonmarcos.cloudnav.CockpitMode): Int = when (mode.speedUnit) {
        "kn"  -> if (mode.id == "airplane") 500 else 60   // boat vs aircraft groundspeed
        "mph" -> 120
        else  -> 180                                       // km/h
    }

    fun speedUnitLabel(u: String) = when (u) { "kn" -> "kn"; "mph" -> "mph"; else -> "km/h" }
    fun kmhTo(u: String, kmh: Double) = when (u) { "kn" -> kmh / 1.852; "mph" -> kmh / 1.609344; else -> kmh }
    fun metersTo(u: String, m: Double) = if (u == "ft") m * 3.28084 else m
}

// ── shared helpers ───────────────────────────────────────────────────────────
private abstract class GaugeBase(ctx: Context, val accent: Int) : View(ctx), CockpitInstrument {
    override val view: View get() = this
    protected val d = resources.displayMetrics.density
    protected fun dp(v: Float) = v * d
    protected val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CockpitGauges.BG }
    protected val panel = RectF()
    protected fun drawPanel(c: Canvas) {
        panel.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        c.drawRoundRect(panel, dp(16f), dp(16f), bgPaint)
    }
    protected fun text(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = dp(size); textAlign = align
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
}

// ── circular speed dial ──────────────────────────────────────────────────────
private class SpeedGauge(ctx: Context, accent: Int, private val maxValue: Int) : GaugeBase(ctx, accent) {
    private var value = 0.0
    private var unit = "km/h"
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF2A3140.toInt()
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = accent
    }
    private val bigTxt = text(30f, CockpitGauges.TEXT, bold = true)
    private val unitTxt = text(11f, CockpitGauges.SUB)
    private val oval = RectF()
    private val startAngle = 135f
    private val sweepMax = 270f

    override fun onSpeed(v: Double, unit: String) { value = max(0.0, v); this.unit = unit; invalidate() }

    override fun onDraw(c: Canvas) {
        drawPanel(c)
        val stroke = dp(9f); track.strokeWidth = stroke; arc.strokeWidth = stroke
        val pad = dp(16f) + stroke
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - pad
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, startAngle, sweepMax, false, track)
        val frac = min(1.0, value / maxValue).toFloat()
        c.drawArc(oval, startAngle, sweepMax * frac, false, arc)
        c.drawText(value.roundToInt().toString(), cx, cy + dp(6f), bigTxt)
        c.drawText(unit, cx, cy + dp(24f), unitTxt)
    }
}

// ── compass rose ─────────────────────────────────────────────────────────────
private class CompassRose(ctx: Context, accent: Int) : GaugeBase(ctx, accent) {
    private var heading = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF2A3140.toInt() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CockpitGauges.SUB }
    private val cardinal = text(13f, CockpitGauges.TEXT, bold = true)
    private val hdgTxt = text(24f, CockpitGauges.TEXT, bold = true)
    private val lubber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val subTxt = text(10f, CockpitGauges.SUB)

    override fun onHeading(deg: Float) { heading = ((deg % 360f) + 360f) % 360f; invalidate() }

    override fun onDraw(c: Canvas) {
        drawPanel(c)
        ring.strokeWidth = dp(2f)
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - dp(22f)
        c.drawCircle(cx, cy, r, ring)

        c.save()
        c.rotate(-heading, cx, cy)
        for (a in 0 until 360 step 30) {
            val rad = Math.toRadians(a.toDouble())
            val outer = r; val inner = r - dp(if (a % 90 == 0) 12f else 7f)
            val sx = cx + (sin(rad) * outer).toFloat(); val sy = cy - (cos(rad) * outer).toFloat()
            val ex = cx + (sin(rad) * inner).toFloat(); val ey = cy - (cos(rad) * inner).toFloat()
            tick.strokeWidth = dp(2f)
            c.drawLine(sx, sy, ex, ey, tick)
            val label = when (a) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> null }
            if (label != null) {
                val lx = cx + (sin(rad) * (r - dp(24f))).toFloat()
                val ly = cy - (cos(rad) * (r - dp(24f))).toFloat() + dp(5f)
                cardinal.color = if (label == "N") accent else CockpitGauges.TEXT
                c.drawText(label, lx, ly, cardinal)
            }
        }
        c.restore()

        // Fixed lubber line (top) + centre heading readout.
        val path = Path().apply {
            moveTo(cx, cy - r - dp(2f)); lineTo(cx - dp(7f), cy - r + dp(10f)); lineTo(cx + dp(7f), cy - r + dp(10f)); close()
        }
        c.drawPath(path, lubber)
        c.drawText("%03d°".format(heading.roundToInt() % 360), cx, cy + dp(4f), hdgTxt)
        c.drawText("HEADING", cx, cy + dp(20f), subTxt)
    }
}

// ── artificial horizon (attitude indicator) ──────────────────────────────────
private class AttitudeIndicator(ctx: Context, accent: Int) : GaugeBase(ctx, accent) {
    private var pitch = 0f  // deg, nose-up positive
    private var roll = 0f   // deg, right-wing-down positive
    private val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A7BD5.toInt() }
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A5A2B.toInt() }
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(2f) }
    private val ladder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); strokeWidth = dp(1.5f) }
    private val ladderTxt = text(9f, Color.WHITE)
    private val wing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = dp(3f) }
    private val clip = Path()

    override fun onAttitude(pitch: Float, roll: Float) {
        this.pitch = pitch.coerceIn(-90f, 90f); this.roll = roll; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - dp(6f)
        clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)

        // pixels per degree of pitch
        val ppd = r / 45f
        c.save()
        c.rotate(-roll, cx, cy)
        val horizonY = cy + pitch * ppd
        c.drawRect(cx - r * 2, horizonY - r * 3, cx + r * 2, horizonY, sky)
        c.drawRect(cx - r * 2, horizonY, cx + r * 2, horizonY + r * 3, ground)
        c.drawLine(cx - r * 1.5f, horizonY, cx + r * 1.5f, horizonY, horizon)
        // pitch ladder every 10°
        for (p in -30..30 step 10) {
            if (p == 0) continue
            val y = cy + (pitch - p) * ppd
            val w = if (p % 20 == 0) dp(26f) else dp(15f)
            c.drawLine(cx - w, y, cx + w, y, ladder)
            c.drawText(abs(p).toString(), cx + w + dp(10f), y + dp(3f), ladderTxt)
        }
        c.restore()
        c.restore()

        // Fixed aircraft symbol (centre) + bank arc pointer.
        c.drawLine(cx - dp(24f), cy, cx - dp(8f), cy, wing)
        c.drawLine(cx + dp(8f), cy, cx + dp(24f), cy, wing)
        c.drawCircle(cx, cy, dp(2.5f), wing)
        val bank = Path().apply {
            moveTo(cx, cy - r); lineTo(cx - dp(6f), cy - r + dp(11f)); lineTo(cx + dp(6f), cy - r + dp(11f)); close()
        }
        c.save(); c.rotate(-roll, cx, cy); c.drawPath(bank, wing); c.restore()
    }
}

// ── vertical altitude tape ───────────────────────────────────────────────────
private class AltitudeTape(ctx: Context, accent: Int, private val unit: String) : GaugeBase(ctx, accent) {
    private var value = 0.0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CockpitGauges.SUB; strokeWidth = dp(1.5f) }
    private val scaleTxt = text(10f, CockpitGauges.SUB, align = Paint.Align.RIGHT)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val boxTxt = text(16f, 0xFF10141C.toInt(), bold = true)
    private val label = text(10f, CockpitGauges.SUB)
    private val box = RectF()

    override fun onAltitude(v: Double) { value = v; invalidate() }

    override fun onDraw(c: Canvas) {
        drawPanel(c)
        val cx = width / 2f; val cy = height / 2f
        val step = if (unit == "ft") 100.0 else 20.0     // one labelled tick
        val ppUnit = dp(0.35f)                            // px per unit — compact tape
        val rightX = width - dp(10f)
        c.save(); c.clipRect(dp(4f), dp(6f), width - dp(4f), height - dp(6f))
        var mark = (value / step).roundToInt() * step - 4 * step
        var i = 0
        while (i <= 8) {
            val y = cy + ((value - mark) * ppUnit).toFloat()
            c.drawLine(rightX - dp(10f), y, rightX, y, line)
            c.drawText(mark.roundToInt().toString(), rightX - dp(14f), y + dp(4f), scaleTxt)
            mark += step; i++
        }
        c.restore()
        // Centre readout box.
        box.set(dp(4f), cy - dp(15f), width - dp(4f), cy + dp(15f))
        c.drawRoundRect(box, dp(5f), dp(5f), boxPaint)
        c.drawText(value.roundToInt().toString(), cx, cy + dp(6f), boxTxt)
        c.drawText("ALT $unit", cx, dp(18f), label)
    }
}

// ── vertical speed indicator ─────────────────────────────────────────────────
private class VSpeedGauge(ctx: Context, accent: Int, private val altUnit: String) : GaugeBase(ctx, accent) {
    private var mps = 0.0
    private val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3140.toInt(); strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = dp(10f); strokeCap = Paint.Cap.ROUND }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CockpitGauges.SUB; strokeWidth = dp(1.5f) }
    private val valTxt = text(15f, CockpitGauges.TEXT, bold = true)
    private val label = text(10f, CockpitGauges.SUB)

    override fun onVerticalMps(v: Double) { mps = v; invalidate() }

    override fun onDraw(c: Canvas) {
        drawPanel(c)
        val cx = width / 2f; val cy = height / 2f
        val half = min(height, width).toFloat() / 2f - dp(30f)
        c.drawLine(cx, cy - half, cx, cy + half, scale)
        c.drawLine(cx - dp(18f), cy, cx + dp(18f), cy, zero)
        // Display in ft/min for aircraft, else m/s. Full scale ±maxDisp.
        val display: Double; val maxDisp: Double; val unit: String
        if (altUnit == "ft") { display = mps * 196.85; maxDisp = 2000.0; unit = "ft/min" }
        else { display = mps; maxDisp = 5.0; unit = "m/s" }
        val frac = (display / maxDisp).coerceIn(-1.0, 1.0)
        val endY = cy - (frac * half).toFloat()
        c.drawLine(cx, cy, cx, endY, bar)
        c.drawText(if (display >= 0) "+%.0f".format(display) else "%.0f".format(display), cx, height - dp(20f), valTxt)
        c.drawText(unit, cx, height - dp(6f), label)
    }
}

// ── text readout (avg / dist / eta / grade / position / track) ────────────────
private class MetricView(ctx: Context, accent: Int, private val label: String) : GaugeBase(ctx, accent) {
    private var line1 = "—"
    private var line2: String? = null
    private val big = text(22f, CockpitGauges.TEXT, bold = true)
    private val mid = text(14f, CockpitGauges.TEXT, bold = true)
    private val lbl = text(11f, accent)

    override fun onMetric(value: String, second: String?) { line1 = value; line2 = second; invalidate() }

    override fun onDraw(c: Canvas) {
        drawPanel(c)
        val cx = width / 2f; val cy = height / 2f
        if (line2 == null) {
            c.drawText(line1, cx, cy + dp(4f), big)
        } else {
            c.drawText(line1, cx, cy - dp(6f), mid)
            c.drawText(line2!!, cx, cy + dp(14f), mid)
        }
        c.drawText(label, cx, height - dp(12f), lbl)
    }
}
