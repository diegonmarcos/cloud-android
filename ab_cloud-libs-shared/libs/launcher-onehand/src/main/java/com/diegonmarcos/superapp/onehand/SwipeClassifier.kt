package com.diegonmarcos.superapp.onehand

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PURE gesture math (no Android deps → JVM-testable). One Hand Operation+ model:
 * the handle activates anywhere along the edge; you swipe INWARD and the TILT of
 * that drag picks one of seven sectors — top_outer / top / top_middle / center /
 * down_middle / down / down_outer (center = straight inward). Bottom edge →
 * left/center/right (3 sectors). `dx`/`dy` are end-minus-start pixels.
 *
 * Angle bands (0° = straight inward, ±90° = parallel to edge):
 *   center      |a| ≤ 10°
 *   top_middle  14°..30°   (neg side)
 *   down_middle 14°..30°   (pos side)
 *   top         34°..50°   (neg side)
 *   down        34°..50°   (pos side)
 *   top_outer   54°..72°   (neg side)
 *   down_outer  54°..72°   (pos side)
 *   dead        elsewhere → cancel
 */
object SwipeClassifier {
    private const val CENTER_HALF     = 10.0   // center band
    private const val MID_MIN         = 14.0   // top_middle / down_middle band
    private const val MID_MAX         = 30.0
    private const val OUTER_MIN       = 34.0   // top / down band
    private const val OUTER_MAX       = 50.0
    private const val OUTERMOST_MIN   = 54.0   // top_outer / down_outer band
    private const val OUTERMOST_MAX   = 72.0

    /** Sector key, or null for a dead-zone / outward / too-shallow drag (= cancel). */
    fun sector(edge: OneHandConfig.Edge, dx: Float, dy: Float): String? {
        val (inward, lateral, outerNeg, neg, negMid, posMid, pos, outerPos) = when (edge) {
            OneHandConfig.Edge.RIGHT -> Axes8(-dx, dy, "top_outer", "top", "top_middle", "down_middle", "down", "down_outer")
            OneHandConfig.Edge.LEFT -> Axes8(dx, dy, "top_outer", "top", "top_middle", "down_middle", "down", "down_outer")
            // Bottom edge keeps the original 3-sector model (left/center/right)
            OneHandConfig.Edge.BOTTOM -> return sectorBottom(dx, dy)
        }
        if (inward <= 0f) return null
        val angleDeg = Math.toDegrees(atan2(lateral.toDouble(), inward.toDouble()))
        val a = abs(angleDeg)
        return when {
            a <= CENTER_HALF -> "center"
            a in MID_MIN..MID_MAX -> if (angleDeg < 0) negMid else posMid
            a in OUTER_MIN..OUTER_MAX -> if (angleDeg < 0) neg else pos
            a in OUTERMOST_MIN..OUTERMOST_MAX -> if (angleDeg < 0) outerNeg else outerPos
            else -> null
        }
    }

    private fun sectorBottom(dx: Float, dy: Float): String? {
        val inward = -dy
        val lateral = dx
        if (inward <= 0f) return null
        val angleDeg = Math.toDegrees(atan2(lateral.toDouble(), inward.toDouble()))
        val a = abs(angleDeg)
        return when {
            a <= 22.0 -> "center"
            a in 32.0..68.0 -> if (angleDeg < 0) "left" else "right"
            else -> null
        }
    }

    /** Sector once past the activation threshold, else null. */
    fun classify(edge: OneHandConfig.Edge, dx: Float, dy: Float, thresholdPx: Int): String? {
        if (hypot(dx, dy) < thresholdPx) return null
        return sector(edge, dx, dy)
    }

    private data class Axes8(
        val inward: Float, val lateral: Float,
        val outerNeg: String, val neg: String, val negMid: String,
        val posMid: String, val pos: String, val outerPos: String,
    )
}
