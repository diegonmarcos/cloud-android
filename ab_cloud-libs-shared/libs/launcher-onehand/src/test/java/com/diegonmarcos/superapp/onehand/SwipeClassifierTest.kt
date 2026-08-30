package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inward angle-sectors: top_outer/top/top_middle/center/down_middle/down/down_outer
 *  (sides), left/center/right (bottom). thr=24. */
class SwipeClassifierTest {
    private val R = OneHandConfig.Edge.RIGHT
    private val L = OneHandConfig.Edge.LEFT
    private val B = OneHandConfig.Edge.BOTTOM
    private fun c(edge: OneHandConfig.Edge, dx: Float, dy: Float) =
        SwipeClassifier.classify(edge, dx, dy, 24)

    /** Builds a swipe of length 100px at `angleDeg` off straight-inward, tilted
     *  toward the negative (top-ish) side when [neg], else positive (down-ish). */
    private fun swipe(edge: OneHandConfig.Edge, angleDeg: Double, neg: Boolean): Pair<Float, Float> {
        val len = 100.0
        val inward = len * kotlin_cos(angleDeg)
        val lateral = (if (neg) -1 else 1) * len * kotlin_sin(angleDeg)
        // inward axis: RIGHT/LEFT -> -dx/dx respectively; lateral axis -> dy
        val dx = if (edge == R) -inward else inward
        return Pair(dx.toFloat(), lateral.toFloat())
    }

    private fun kotlin_cos(deg: Double) = Math.cos(Math.toRadians(deg))
    private fun kotlin_sin(deg: Double) = Math.sin(Math.toRadians(deg))

    @Test fun rightCenter() = assertEquals("center", c(R, -60f, 0f))
    @Test fun rightTopMiddleTilt() {
        val (dx, dy) = swipe(R, 22.0, neg = true)
        assertEquals("top_middle", c(R, dx, dy))
    }
    @Test fun rightDownMiddleTilt() {
        val (dx, dy) = swipe(R, 22.0, neg = false)
        assertEquals("down_middle", c(R, dx, dy))
    }
    @Test fun rightTopTilt() {
        val (dx, dy) = swipe(R, 42.0, neg = true)
        assertEquals("top", c(R, dx, dy))
    }
    @Test fun rightDownTilt() {
        val (dx, dy) = swipe(R, 42.0, neg = false)
        assertEquals("down", c(R, dx, dy))
    }
    @Test fun rightTopOuterTilt() {
        val (dx, dy) = swipe(R, 63.0, neg = true)
        assertEquals("top_outer", c(R, dx, dy))
    }
    @Test fun rightDownOuterTilt() {
        val (dx, dy) = swipe(R, 63.0, neg = false)
        assertEquals("down_outer", c(R, dx, dy))
    }
    @Test fun rightOutwardNull() = assertNull(c(R, 60f, 0f))
    @Test fun pureVerticalNoInwardNull() = assertNull(c(R, 0f, -60f))

    @Test fun leftCenter() = assertEquals("center", c(L, 60f, 0f))
    @Test fun leftTopMiddleTilt() {
        val (dx, dy) = swipe(L, 22.0, neg = true)
        assertEquals("top_middle", c(L, dx, dy))
    }
    @Test fun leftDownTilt() {
        val (dx, dy) = swipe(L, 42.0, neg = false)
        assertEquals("down", c(L, dx, dy))
    }
    @Test fun leftTopOuterTilt() {
        val (dx, dy) = swipe(L, 63.0, neg = true)
        assertEquals("top_outer", c(L, dx, dy))
    }
    @Test fun leftDownOuterTilt() {
        val (dx, dy) = swipe(L, 63.0, neg = false)
        assertEquals("down_outer", c(L, dx, dy))
    }

    @Test fun bottomCenter() = assertEquals("center", c(B, 0f, -60f))
    @Test fun bottomLeftTilt() = assertEquals("left", c(B, -60f, -60f))
    @Test fun bottomRightTilt() = assertEquals("right", c(B, 60f, -60f))
    @Test fun bottomDownNull() = assertNull(c(B, 0f, 60f))
    @Test fun belowThresholdNull() = assertNull(c(R, -10f, 0f))

    // Dead zones (release here = cancel): between center & mid (~12°)…
    @Test fun gapBetweenCenterAndMidIsNull() {
        val (dx, dy) = swipe(R, 12.0, neg = true)
        assertNull(c(R, dx, dy))
    }
    // …between mid & outer (~32°)…
    @Test fun gapBetweenMidAndOuterIsNull() {
        val (dx, dy) = swipe(R, 32.0, neg = true)
        assertNull(c(R, dx, dy))
    }
    // …between outer & outermost (~52°)…
    @Test fun gapBetweenOuterAndOutermostIsNull() {
        val (dx, dy) = swipe(R, 52.0, neg = true)
        assertNull(c(R, dx, dy))
    }
    // …and past the outermost band, near-along-edge (~80°).
    @Test fun steepIsNull() = assertNull(c(R, -20f, -80f))
}
