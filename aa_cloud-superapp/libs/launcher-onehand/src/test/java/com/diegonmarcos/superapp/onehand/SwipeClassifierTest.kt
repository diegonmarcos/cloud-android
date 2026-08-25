package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inward angle-sectors: top/center/down (sides), left/center/right (bottom). thr=24. */
class SwipeClassifierTest {
    private val R = OneHandConfig.Edge.RIGHT
    private val L = OneHandConfig.Edge.LEFT
    private val B = OneHandConfig.Edge.BOTTOM
    private fun c(edge: OneHandConfig.Edge, dx: Float, dy: Float) =
        SwipeClassifier.classify(edge, dx, dy, 24)

    @Test fun rightCenter() = assertEquals("center", c(R, -60f, 0f))
    @Test fun rightTopTilt() = assertEquals("top", c(R, -60f, -60f))
    @Test fun rightDownTilt() = assertEquals("down", c(R, -60f, 60f))
    @Test fun rightOutwardNull() = assertNull(c(R, 60f, 0f))
    @Test fun pureVerticalNoInwardNull() = assertNull(c(R, 0f, -60f))
    @Test fun leftCenter() = assertEquals("center", c(L, 60f, 0f))
    @Test fun leftDownTilt() = assertEquals("down", c(L, 60f, 60f))
    @Test fun bottomCenter() = assertEquals("center", c(B, 0f, -60f))
    @Test fun bottomLeftTilt() = assertEquals("left", c(B, -60f, -60f))
    @Test fun bottomRightTilt() = assertEquals("right", c(B, 60f, -60f))
    @Test fun bottomDownNull() = assertNull(c(B, 0f, 60f))
    @Test fun belowThresholdNull() = assertNull(c(R, -10f, 0f))
    // Dead zones (release here = cancel): seam between center & diagonal (~26°)…
    @Test fun gapBetweenCenterAndTopIsNull() = assertNull(c(R, -60f, -30f))
    // …and the steep near-along-edge zone (~76°).
    @Test fun steepIsNull() = assertNull(c(R, -20f, -80f))
}
