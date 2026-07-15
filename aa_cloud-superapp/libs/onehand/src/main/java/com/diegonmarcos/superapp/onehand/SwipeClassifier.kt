package com.diegonmarcos.superapp.onehand

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PURE gesture math (no Android deps → JVM-testable). One Hand Operation+ style:
 * you swipe INWARD from the edge and the TILT of that inward drag picks the
 * direction (diagonal), not a parallel up/down swipe. `dx`/`dy` are end-minus-
 * start pixels.
 *
 *  - [sector]: the edge-relative direction key (in|up|down for sides,
 *    up|left|right for bottom), or null for an outward / too-shallow drag.
 *  - [classify]: `<magnitude>_<sector>` where magnitude = short|long by distance.
 */
object SwipeClassifier {
    // Half-angle (deg) of the "straight inward" cone; beyond it → diagonal.
    private const val STRAIGHT_DEG = 30.0

    fun sector(edge: OneHandConfig.Edge, dx: Float, dy: Float): String? {
        val (inward, lateral, straight, neg, pos) = when (edge) {
            OneHandConfig.Edge.RIGHT -> Axes(-dx, dy, "in", "up", "down")
            OneHandConfig.Edge.LEFT -> Axes(dx, dy, "in", "up", "down")
            OneHandConfig.Edge.BOTTOM -> Axes(-dy, dx, "up", "left", "right")
        }
        if (inward <= 0f) return null // outward / along-edge only → no gesture
        val angleDeg = Math.toDegrees(atan2(lateral.toDouble(), inward.toDouble()))
        return when {
            abs(angleDeg) <= STRAIGHT_DEG -> straight
            angleDeg < 0 -> neg
            else -> pos
        }
    }

    fun classify(
        edge: OneHandConfig.Edge, dx: Float, dy: Float,
        thresholdPx: Int, longSwipePx: Int,
    ): String? {
        if (hypot(dx, dy) < thresholdPx) return null
        val dir = sector(edge, dx, dy) ?: return null
        val magnitude = if (hypot(dx, dy) >= longSwipePx) "long" else "short"
        return "${magnitude}_$dir"
    }

    private data class Axes(
        val inward: Float, val lateral: Float,
        val straight: String, val neg: String, val pos: String,
    )
}
