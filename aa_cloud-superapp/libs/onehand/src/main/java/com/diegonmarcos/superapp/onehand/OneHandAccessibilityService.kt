package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Performs global actions (Back/Home/Recents/…) on behalf of the edge handle.
 * The overlay service detects the swipe and calls [instance]?.perform(action);
 * same-process static ref is the standard way to reach a live a11y service.
 */
class OneHandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    fun perform(action: OneHandAction) {
        if (action.supported) performGlobalAction(action.globalAction)
    }

    companion object {
        // ponytail: single-process app, one live a11y instance — a static ref
        // beats an IPC bus. Nulled in onDestroy so a stale ref never fires.
        @Volatile var instance: OneHandAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
