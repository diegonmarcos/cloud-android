package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.os.Build

/**
 * Global actions the edge handle can trigger via the accessibility service —
 * the full One Hand Operation+ vocabulary the AccessibilityService API can
 * actually perform. `minApi` gates actions whose GLOBAL_ACTION_* constant
 * arrived after our minSdk 26; below that API the action is a NONE no-op.
 * NONE lets a gesture be explicitly unmapped in build.json.
 */
enum class OneHandAction(val globalAction: Int, val minApi: Int = 21) {
    NONE(-1),
    BACK(AccessibilityService.GLOBAL_ACTION_BACK),
    HOME(AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
    POWER_DIALOG(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
    SPLIT_SCREEN(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, 24),
    LOCK_SCREEN(8 /* GLOBAL_ACTION_LOCK_SCREEN */, 28),
    SCREENSHOT(9 /* GLOBAL_ACTION_TAKE_SCREENSHOT */, 30),
    DISMISS_SHADE(15 /* GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE */, 31),
    ALL_APPS(14 /* GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS */, 31);

    val supported: Boolean get() = this != NONE && Build.VERSION.SDK_INT >= minApi

    companion object {
        fun from(id: String?): OneHandAction? =
            entries.firstOrNull { it.name.equals(id?.replace('-', '_'), true) }
    }
}
