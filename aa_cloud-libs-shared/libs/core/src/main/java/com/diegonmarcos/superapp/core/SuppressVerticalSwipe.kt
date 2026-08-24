package com.diegonmarcos.superapp.core

/**
 * Implemented by fragments whose own content (e.g. an inner WebView in
 * the internal browser tab) needs to own VERTICAL flings. MainActivity's
 * handleVerticalFling checks this on every fling and skips the
 * open-app-drawer / close-app-drawer path when the current fragment
 * returns true, so the browser can scroll, pull-to-refresh, or fire its
 * own swipe-up navigation gesture without the activity stealing it.
 *
 * Mirror of [SuppressHorizontalSwipe] — same rationale, opposite axis.
 */
interface SuppressVerticalSwipe {
    fun suppressVerticalSwipe(): Boolean
}
