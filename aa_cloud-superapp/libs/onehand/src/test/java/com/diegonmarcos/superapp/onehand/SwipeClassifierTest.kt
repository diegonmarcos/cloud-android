package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeClassifierTest {
    private val R = OneHandConfig.Edge.RIGHT
    private val L = OneHandConfig.Edge.LEFT
    private fun c(edge: OneHandConfig.Edge, dx: Float, dy: Float) =
        SwipeClassifier.classify(edge, dx, dy, dx * 10, dy * 10, 24, 100)

    @Test fun rightEdgeInwardIsSwipeIn() = assertEquals("swipe_in", c(R, -120f, 5f))
    @Test fun leftEdgeInwardIsSwipeIn() = assertEquals("swipe_in", c(L, 120f, 5f))
    @Test fun rightEdgeOutwardIsNull() = assertNull(c(R, 120f, 5f))   // away from center
    @Test fun swipeUp() = assertEquals("swipe_up", c(R, 5f, -120f))
    @Test fun swipeDown() = assertEquals("swipe_down", c(R, 5f, 120f))
    @Test fun belowThresholdIsNull() = assertNull(c(R, -10f, 3f))
    @Test fun lowVelocityIsNull() =
        assertNull(SwipeClassifier.classify(R, -120f, 5f, -50f, 0f, 24, 100))
}
