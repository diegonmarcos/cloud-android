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
    private const val STRAIGHT_DEG = 30.0 // half-cone of "center"

    /** Sector key, or null for an outward / too-shallow drag. */
    fun sector(edge: OneHandConfig.Edge, dx: Float, dy: Float): String? {
        val (inward, lateral, neg, pos) = when (edge) {
            OneHandConfig.Edge.RIGHT -> Axes(-dx, dy, "top", "down")
            OneHandConfig.Edge.LEFT -> Axes(dx, dy, "top", "down")
            OneHandConfig.Edge.BOTTOM -> Axes(-dy, dx, "left", "right")
        }
        if (inward <= 0f) return null
        val angleDeg = Math.toDegrees(atan2(lateral.toDouble(), inward.toDouble()))
        return when {
            abs(angleDeg) <= STRAIGHT_DEG -> "center"
            angleDeg < 0 -> neg
            else -> pos
        }
    }

    /** Sector once past the activation threshold, else null. */
    fun classify(edge: OneHandConfig.Edge, dx: Float, dy: Float, thresholdPx: Int): String? {
        if (hypot(dx, dy) < thresholdPx) return null
        return sector(edge, dx, dy)
    }

    private data class Axes(val inward: Float, val lateral: Float, val neg: String, val pos: String)
}
