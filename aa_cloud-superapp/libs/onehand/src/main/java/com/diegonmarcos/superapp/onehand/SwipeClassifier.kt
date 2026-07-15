package com.diegonmarcos.superapp.onehand

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PURE gesture math (no Android deps → JVM-testable). One Hand Operation+ model:
 * the handle activates anywhere along the edge; you swipe INWARD and the TILT of
 * that drag picks one of three sectors — top / center / down (center = straight
 * inward). `dx`/`dy` are end-minus-start pixels. Bottom edge → left/center/right.
 */
object SwipeClassifier {
    // Each sector is a BAND around its canonical angle; the gaps between bands
    // (and the steep/outward zones) are dead zones — releasing there returns
    // null, which cancels (closes the menu without firing).
    private const val CENTER_HALF = 22.0   // center = |angle| ≤ 22
    private const val DIAG_MIN = 32.0      // diagonal band = 32..68 (gap 22..32)
    private const val DIAG_MAX = 68.0      // beyond 68 (near along-edge) = dead

    /** Sector key, or null for a dead-zone / outward / too-shallow drag (= cancel). */
    fun sector(edge: OneHandConfig.Edge, dx: Float, dy: Float): String? {
        val (inward, lateral, neg, pos) = when (edge) {
            OneHandConfig.Edge.RIGHT -> Axes(-dx, dy, "top", "down")
            OneHandConfig.Edge.LEFT -> Axes(dx, dy, "top", "down")
            OneHandConfig.Edge.BOTTOM -> Axes(-dy, dx, "left", "right")
        }
        if (inward <= 0f) return null
        val angleDeg = Math.toDegrees(atan2(lateral.toDouble(), inward.toDouble()))
        val a = abs(angleDeg)
        return when {
            a <= CENTER_HALF -> "center"
            a in DIAG_MIN..DIAG_MAX -> if (angleDeg < 0) neg else pos
            else -> null // dead zone → cancel
        }
    }

    /** Sector once past the activation threshold, else null. */
    fun classify(edge: OneHandConfig.Edge, dx: Float, dy: Float, thresholdPx: Int): String? {
        if (hypot(dx, dy) < thresholdPx) return null
        return sector(edge, dx, dy)
    }

    private data class Axes(val inward: Float, val lateral: Float, val neg: String, val pos: String)
}
