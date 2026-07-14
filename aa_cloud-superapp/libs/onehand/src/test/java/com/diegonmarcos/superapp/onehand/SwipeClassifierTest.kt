package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeClassifierTest {
    private val R = OneHandConfig.Edge.RIGHT
    private val L = OneHandConfig.Edge.LEFT
    private val B = OneHandConfig.Edge.BOTTOM
    // thresholds: swipe=24, long=120, velocity=100
    private fun c(edge: OneHandConfig.Edge, dx: Float, dy: Float) =
        SwipeClassifier.classify(edge, dx, dy, dx * 10, dy * 10, 24, 120, 100)

    @Test fun rightShortInward() = assertEquals("short_in", c(R, -60f, 3f))
    @Test fun rightLongInward() = assertEquals("long_in", c(R, -140f, 3f))
    @Test fun leftInward() = assertEquals("short_in", c(L, 60f, 3f))
    @Test fun rightOutwardIsNull() = assertNull(c(R, 60f, 3f))
    @Test fun sideSwipeUp() = assertEquals("short_up", c(R, 3f, -60f))
    @Test fun sideLongDown() = assertEquals("long_down", c(R, 3f, 140f))
    @Test fun bottomUp() = assertEquals("short_up", c(B, 3f, -60f))
    @Test fun bottomLeft() = assertEquals("short_left", c(B, -60f, 3f))
    @Test fun bottomDownIsNull() = assertNull(c(B, 3f, 60f)) // outward off bottom
    @Test fun belowThresholdIsNull() = assertNull(c(R, -10f, 3f))
    @Test fun lowVelocityIsNull() =
        assertNull(SwipeClassifier.classify(R, -60f, 3f, -50f, 0f, 24, 120, 100))
}
