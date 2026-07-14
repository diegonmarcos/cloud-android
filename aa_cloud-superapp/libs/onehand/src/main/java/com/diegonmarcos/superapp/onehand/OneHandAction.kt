package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService

/** Global actions the edge handle can trigger via the accessibility service. */
enum class OneHandAction(val globalAction: Int) {
    BACK(AccessibilityService.GLOBAL_ACTION_BACK),
    HOME(AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);

    companion object {
        fun from(id: String?): OneHandAction? =
            entries.firstOrNull { it.name.equals(id?.replace('-', '_'), true) }
    }
}
