package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Angle-sector inward-swipe mechanic (One Hand Operation+ style). thr=24 long=120. */
class SwipeClassifierTest {
    private val R = OneHandConfig.Edge.RIGHT
    private val L = OneHandConfig.Edge.LEFT
    private val B = OneHandConfig.Edge.BOTTOM
    private fun c(edge: OneHandConfig.Edge, dx: Float, dy: Float) =
        SwipeClassifier.classify(edge, dx, dy, 24, 120)

    @Test fun rightStraightIn() = assertEquals("short_in", c(R, -60f, 0f))
    @Test fun rightLongIn() = assertEquals("long_in", c(R, -140f, 0f))
    @Test fun rightInwardUpDiagonal() = assertEquals("short_up", c(R, -60f, -60f))
    @Test fun rightInwardDownDiagonal() = assertEquals("short_down", c(R, -60f, 60f))
    @Test fun rightOutwardIsNull() = assertNull(c(R, 60f, 0f))
    @Test fun pureVerticalNoInwardIsNull() = assertNull(c(R, 0f, -60f))
    @Test fun leftStraightIn() = assertEquals("short_in", c(L, 60f, 0f))
    @Test fun leftInwardDownDiagonal() = assertEquals("short_down", c(L, 60f, 60f))
    @Test fun bottomStraightUp() = assertEquals("short_up", c(B, 0f, -60f))
    @Test fun bottomUpLeftDiagonal() = assertEquals("short_left", c(B, -60f, -60f))
    @Test fun bottomUpRightDiagonal() = assertEquals("short_right", c(B, 60f, -60f))
    @Test fun bottomDownIsNull() = assertNull(c(B, 0f, 60f))
    @Test fun belowThresholdIsNull() = assertNull(c(R, -10f, 0f))
}
