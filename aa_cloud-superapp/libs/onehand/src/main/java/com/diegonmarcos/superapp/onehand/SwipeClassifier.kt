package com.diegonmarcos.superapp.onehand

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PURE gesture math (no Android deps → JVM-testable). One Hand Operation+ model:
 * the handle activates anywhere along the edge; you swipe INWARD and the TILT of
 * that drag picks one of five sectors — top / top_middle / center / down_middle /
 * down (center = straight inward). Bottom edge → left/center/right (3 sectors).
 * `dx`/`dy` are end-minus-start pixels.
 *
 * Angle bands (0° = straight inward, ±90° = parallel to edge):
 *   center      |a| ≤ 14°
 *   top_middle  20°..38°   (neg side)
 *   down_middle 20°..38°   (pos side)
 *   top         44°..65°   (neg side)
 *   down        44°..65°   (pos side)
 *   dead        elsewhere → cancel
 */
object SwipeClassifier {
    private const val CENTER_HALF   = 14.0   // center band
    private const val MID_MIN       = 20.0   // top_middle / down_middle band
    private const val MID_MAX       = 38.0
    private const val OUTER_MIN     = 44.0   // top / down band
    private const val OUTER_MAX     = 65.0

    /** Sector key, or null for a dead-zone / outward / too-shallow drag (= cancel). */
    fun sector(edge: OneHandConfig.Edge, dx: Float, dy: Float): String? {
        val (inward, lateral, neg, negMid, pos, posMid) = when (edge) {
            OneHandConfig.Edge.RIGHT -> Axes6(-dx, dy, "top", "top_middle", "down", "down_middle")
            OneHandConfig.Edge.LEFT -> Axes6(dx, dy, "top", "top_middle", "down", "down_middle")
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

    private data class Axes6(
        val inward: Float, val lateral: Float,
        val neg: String, val negMid: String,
        val pos: String, val posMid: String,
    )
}
