package com.diegonmarcos.superapp.onehand

import kotlin.math.abs

/**
 * PURE gesture math (no Android deps → JVM-testable). Maps a fling to an
 * edge-relative gesture key (`short_in`, `long_up`, …) that indexes a handle's
 * gesture map. `dx`/`dy` are end-minus-start pixels. Returns null below
 * threshold or for an outward (off-screen) swipe. Hold is detected separately
 * (long-press), not here.
 */
object SwipeClassifier {
    fun classify(
        edge: OneHandConfig.Edge,
        dx: Float, dy: Float,
        velocityX: Float, velocityY: Float,
        thresholdPx: Int, longSwipePx: Int, velocityThreshold: Int,
    ): String? {
        val horizontal = abs(dx) > abs(dy)
        val dist = if (horizontal) abs(dx) else abs(dy)
        val vel = if (horizontal) abs(velocityX) else abs(velocityY)
        if (dist < thresholdPx || vel < velocityThreshold) return null

        val dir = direction(edge, dx, dy, horizontal) ?: return null
        val magnitude = if (dist >= longSwipePx) "long" else "short"
        return "${magnitude}_$dir"
    }

    /** Screen direction → edge-relative logical direction; null = outward. */
    private fun direction(edge: OneHandConfig.Edge, dx: Float, dy: Float, horizontal: Boolean): String? =
        when (edge) {
            OneHandConfig.Edge.RIGHT ->
                if (horizontal) { if (dx < 0) "in" else null } else if (dy < 0) "up" else "down"
            OneHandConfig.Edge.LEFT ->
                if (horizontal) { if (dx > 0) "in" else null } else if (dy < 0) "up" else "down"
            OneHandConfig.Edge.BOTTOM ->
                if (!horizontal) { if (dy < 0) "up" else null } else if (dx < 0) "left" else "right"
        }
}
