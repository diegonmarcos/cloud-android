package com.diegonmarcos.cloudnav.core

/**
 * Implemented by fragments whose own content (e.g. an inner WebView in
 * desktop view mode) needs to own horizontal flings. MainActivity's
 * navSwipeGesture checks this on every fling and skips the
 * cycle-bottom-nav path when the current fragment returns true, so the
 * WebView can pan / change page without the activity stealing the
 * gesture.
 */
interface SuppressHorizontalSwipe {
    fun suppressHorizontalSwipe(): Boolean
}
