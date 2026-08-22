package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.provider.Settings

/**
 * Entry point app/ (the Configs/Onehand toggle) calls. The overlay is hosted by
 * the persistent accessibility service (no foreground service, no notification),
 * so enabling/disabling just persists the flag + tells the live service to show
 * or hide the handles. GRANTING the two permissions is the centralized
 * Configs > Permissions page's job — this controller never deep-links.
 */
object OneHandController {

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun accessibilityEnabled(): Boolean = OneHandAccessibilityService.isConnected

    fun ready(ctx: Context): Boolean = canDrawOverlay(ctx) && accessibilityEnabled()

    fun isOn(ctx: Context): Boolean = OneHandPrefs.isEnabled(ctx)

    fun enable(ctx: Context) {
        if (!ready(ctx)) return
        OneHandPrefs.setEnabled(ctx, true)
        OneHandAccessibilityService.instance?.showHandles()
    }

    fun disable(ctx: Context) {
        OneHandPrefs.setEnabled(ctx, false)
        OneHandAccessibilityService.instance?.hideHandles()
    }

    /** Re-read config after a per-swipe edit so a running overlay picks it up. */
    fun refresh(ctx: Context) {
        if (isOn(ctx)) OneHandAccessibilityService.instance?.showHandles()
    }

    /** Turn the handles on for [ms] then auto-off — the safe way to test. */
    fun testActivate(ctx: Context, ms: Long = 30_000L): Boolean {
        if (!ready(ctx)) return false
        OneHandAccessibilityService.instance?.testActivate(ms)
        return true
    }
}
