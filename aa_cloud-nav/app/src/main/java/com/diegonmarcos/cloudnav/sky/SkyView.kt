package com.diegonmarcos.cloudnav.sky

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/**
 * AR-style star field: projects each catalog star's alt/az onto the screen against a
 * "looking direction" that's the device's sensor orientation PLUS a manual drag offset,
 * so it's fully usable (pan + pinch-zoom) even on emulators/devices with no rotation
 * sensor, instead of going permanently blank if TYPE_ROTATION_VECTOR never fires.
 *
 * Scope: stars/constellation lines only, no planets — a real planet renderer needs
 * orbital-mechanics ephemeris data this catalog doesn't have; not attempted here.
 */
class SkyView(context: Context) : View(context) {
    // Sensor-reported orientation (set externally by ConstellationsActivity).
    var sensorAzimuthDeg: Double = 0.0
    var sensorPitchDeg: Double = 0.0
    var observerLatDeg: Double = 0.0
    var observerLonDeg: Double = 0.0

    // User drag offset, added on top of the sensor reading (or used alone if no sensor).
    private var panAzimuthDeg: Double = 0.0
    private var panPitchDeg: Double = 0.0
    private var fovDeg: Double = 70.0

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x552AD8FF; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); textSize = 28f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#05070d") }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Pinch out (scale > 1) should narrow the field of view (zoom in).
            fovDeg = (fovDeg / detector.scaleFactor).coerceIn(15.0, 150.0)
            invalidate()
            return true
        }
    })
    private val panDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val degPerPxX = fovDeg / width.coerceAtLeast(1)
            val degPerPxY = fovDeg / height.coerceAtLeast(1)
            panAzimuthDeg += dx * degPerPxX
            panPitchDeg -= dy * degPerPxY
            panPitchDeg = panPitchDeg.coerceIn(-89.0, 89.0)
            invalidate()
            return true
        }
    })

    init { isClickable = true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) panDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (width == 0 || height == 0) return

        val lookAz = sensorAzimuthDeg + panAzimuthDeg
        val lookAlt = sensorPitchDeg + panPitchDeg
        val now = System.currentTimeMillis()

        val screen = HashMap<Int, FloatArray?>()
        var visibleCount = 0
        StarCatalog.stars.forEachIndexed { i, s ->
            val (alt, az) = SkyMath.altAz(s.raDeg, s.decDeg, observerLatDeg, observerLonDeg, now)
            val p = project(alt, az, lookAz, lookAlt)
            if (p != null) visibleCount++
            screen[i] = p
        }

        for (line in StarCatalog.lines) {
            for ((a, b) in line.starIndices) {
                val pa = screen[a] ?: continue
                val pb = screen[b] ?: continue
                canvas.drawLine(pa[0], pa[1], pb[0], pb[1], linePaint)
            }
        }

        StarCatalog.stars.forEachIndexed { i, s ->
            val p = screen[i] ?: return@forEachIndexed
            val r = (5.0 - s.mag).coerceIn(1.0, 6.0).toFloat()
            canvas.drawCircle(p[0], p[1], r, starPaint)
            if (s.mag < 1.5) canvas.drawText(s.name, p[0] + 10f, p[1], labelPaint)
        }

        if (visibleCount == 0) {
            canvas.drawText("No stars this direction — drag to look around", width / 2f, height / 2f, hintPaint)
        }
    }

    /** null if the star is outside the current field of view. */
    private fun project(altDeg: Double, azDeg: Double, lookAzDeg: Double, lookAltDeg: Double): FloatArray? {
        var dAz = azDeg - lookAzDeg
        dAz = ((dAz + 180.0).mod(360.0)) - 180.0
        val dAlt = altDeg - lookAltDeg
        val half = fovDeg / 2
        if (abs(dAz) > half || abs(dAlt) > half) return null

        val x = (width / 2) + (dAz / half) * (width / 2)
        val y = (height / 2) - (dAlt / half) * (height / 2)
        return floatArrayOf(x.toFloat(), y.toFloat())
    }
}
