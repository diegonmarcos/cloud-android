package com.diegonmarcos.cloudnav.sky

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/**
 * AR-style sky renderer: stars, full IAU constellation lines, Milky Way outline, and the
 * Sun/planets (from real bundled data — see CelestialData/PlanetEphemeris/CREDITS.md),
 * projected against a "looking direction" that's the device's sensor orientation PLUS a
 * manual drag offset, so pan/pinch-zoom always work even without a working sensor.
 *
 * Moons are deliberately NOT rendered: at naked-eye AR-view scale even Jupiter's Galilean
 * moons are sub-pixel/invisible without a telescope-grade zoom this view doesn't have —
 * adding them would be a token gesture, not a real feature, so it's skipped and disclosed
 * rather than faked.
 */
class SkyView(context: Context) : View(context) {
    var sensorAzimuthDeg: Double = 0.0
    var sensorPitchDeg: Double = 0.0
    var observerLatDeg: Double = 0.0
    var observerLonDeg: Double = 0.0

    var stars: List<Star> = emptyList()
    var constellationLines: List<ConstellationSegment> = emptyList()
    var milkyWay: List<SkyPolygon> = emptyList()
    var bodies: List<BodyPosition> = emptyList()

    private var panAzimuthDeg: Double = 0.0
    private var panPitchDeg: Double = 0.0
    private var fovDeg: Double = 70.0

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x442AD8FF; strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val milkyWayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF; style = Paint.Style.FILL
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54A.toInt() }
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7EC8E3.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); textSize = 26f
    }
    private val bodyLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54A.toInt(); textSize = 30f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#05070d") }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
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

        fun projectRaDec(raDeg: Double, decDeg: Double): FloatArray? {
            val (alt, az) = SkyMath.altAz(raDeg, decDeg, observerLatDeg, observerLonDeg, now)
            return project(alt, az, lookAz, lookAlt)
        }

        drawMilkyWay(canvas, ::projectRaDec)

        for (seg in constellationLines) {
            val pa = projectRaDec(seg.a[0], seg.a[1]) ?: continue
            val pb = projectRaDec(seg.b[0], seg.b[1]) ?: continue
            canvas.drawLine(pa[0], pa[1], pb[0], pb[1], linePaint)
        }

        var visibleCount = 0
        for (s in stars) {
            val p = projectRaDec(s.raDeg, s.decDeg) ?: continue
            visibleCount++
            val r = (5.0 - s.mag).coerceIn(0.6, 6.0).toFloat()
            canvas.drawCircle(p[0], p[1], r, starPaint)
        }

        for (b in bodies) {
            val p = projectRaDec(b.raDeg, b.decDeg) ?: continue
            visibleCount++
            if (b.name == "Sun") {
                canvas.drawCircle(p[0], p[1], 16f, sunPaint)
                canvas.drawText("Sun", p[0] + 20f, p[1], bodyLabelPaint)
            } else {
                canvas.drawCircle(p[0], p[1], 6f, planetPaint)
                canvas.drawText(b.name, p[0] + 12f, p[1], labelPaint)
            }
        }

        if (visibleCount == 0) {
            canvas.drawText("Nothing this direction — drag to look around", width / 2f, height / 2f, hintPaint)
        }
    }

    private fun drawMilkyWay(canvas: Canvas, projectRaDec: (Double, Double) -> FloatArray?) {
        for (poly in milkyWay) {
            val path = Path()
            var started = false
            var lastVisible = true
            for (pt in poly.ring) {
                val p = projectRaDec(pt[0], pt[1])
                if (p == null) { lastVisible = false; continue }
                if (!started || !lastVisible) { path.moveTo(p[0], p[1]); started = true }
                else path.lineTo(p[0], p[1])
                lastVisible = true
            }
            if (started) canvas.drawPath(path, milkyWayPaint)
        }
    }

    /** null if the point is outside the current field of view. */
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
