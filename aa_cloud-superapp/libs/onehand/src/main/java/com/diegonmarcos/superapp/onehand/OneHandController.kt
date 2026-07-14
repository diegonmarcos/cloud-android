package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.provider.Settings

/**
 * Entry point app/ (the Configs/Onehand toggle) calls. Reads the two special
 * permission states so the toggle can gate itself, and starts/stops the overlay.
 * GRANTING those permissions is the centralized Configs > Permissions page's job
 * — this controller never deep-links to system settings (no duplicate perm UI).
 */
object OneHandController {

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun accessibilityEnabled(): Boolean = OneHandAccessibilityService.isEnabled

    /** True only once both prerequisites are satisfied. */
    fun ready(ctx: Context): Boolean = canDrawOverlay(ctx) && accessibilityEnabled()

    fun enable(ctx: Context) { if (ready(ctx)) EdgeOverlayService.start(ctx) }

    fun disable(ctx: Context) { EdgeOverlayService.stop(ctx) }
}
