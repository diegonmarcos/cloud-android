package com.diegonmarcos.cloudnav.sky

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.*

/**
 * AR-style star field: projects each catalog star's alt/az against the phone's current
 * pointing direction (from the rotation-vector sensor) onto the screen. Simple
 * orthographic-ish projection around the pointing axis -- not a full sky-dome renderer,
 * good enough for "point phone at sky, see constellation lines" at v1 scope.
 */
class SkyView(context: Context) : View(context) {
    var deviceAzimuthDeg: Double = 0.0
    var devicePitchDeg: Double = 0.0
    var observerLatDeg: Double = 0.0
    var observerLonDeg: Double = 0.0
    var nowMillis: Long = 0L

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x552AD8FF; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); textSize = 28f
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#05070d") }

    private val fovDeg = 70.0

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (width == 0 || height == 0) return

        val screen = HashMap<Int, FloatArray?>()
        StarCatalog.stars.forEachIndexed { i, s ->
            val (alt, az) = SkyMath.altAz(s.raDeg, s.decDeg, observerLatDeg, observerLonDeg, nowMillis)
            screen[i] = project(alt, az)
        }

        linePaint.let { p ->
            for (line in StarCatalog.lines) {
                for ((a, b) in line.starIndices) {
                    val pa = screen[a] ?: continue
                    val pb = screen[b] ?: continue
                    canvas.drawLine(pa[0], pa[1], pb[0], pb[1], p)
                }
            }
        }

        StarCatalog.stars.forEachIndexed { i, s ->
            val p = screen[i] ?: return@forEachIndexed
            val r = (5.0 - s.mag).coerceIn(1.0, 6.0).toFloat()
            canvas.drawCircle(p[0], p[1], r, starPaint)
            if (s.mag < 1.5) canvas.drawText(s.name, p[0] + 10f, p[1], labelPaint)
        }
    }

    /** null if the star is outside the current field of view (behind/beside the phone). */
    private fun project(altDeg: Double, azDeg: Double): FloatArray? {
        var dAz = azDeg - deviceAzimuthDeg
        dAz = ((dAz + 180.0).mod(360.0)) - 180.0
        val dAlt = altDeg - devicePitchDeg
        if (abs(dAz) > fovDeg / 2 || abs(dAlt) > fovDeg / 2) return null

        val half = fovDeg / 2
        val x = (width / 2) + (dAz / half) * (width / 2)
        val y = (height / 2) - (dAlt / half) * (height / 2)
        return floatArrayOf(x.toFloat(), y.toFloat())
    }
}
