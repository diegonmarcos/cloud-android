package com.diegonmarcos.superapp.onehand

import kotlin.math.abs

/**
 * PURE gesture math (no Android deps → JVM-testable). Maps a fling
 * (dx, dy, velocity) to an edge-relative logical gesture key that indexes
 * [OneHandConfig.gestures]. `dx`/`dy` are end-minus-start pixels; `thresholdPx`
 * / `velocityThreshold` come from config. Returns null below threshold.
 */
object SwipeClassifier {
    fun classify(
        edge: OneHandConfig.Edge,
        dx: Float, dy: Float,
        velocityX: Float, velocityY: Float,
        thresholdPx: Int, velocityThreshold: Int,
    ): String? {
        if (abs(dx) > abs(dy)) {
            if (abs(dx) < thresholdPx || abs(velocityX) < velocityThreshold) return null
            // Inward = toward screen center: negative dx from a right edge,
            // positive dx from a left edge.
            val inward = if (edge == OneHandConfig.Edge.RIGHT) dx < 0 else dx > 0
            return if (inward) "swipe_in" else null
        } else {
            if (abs(dy) < thresholdPx || abs(velocityY) < velocityThreshold) return null
            return if (dy < 0) "swipe_up" else "swipe_down"
        }
    }
}
