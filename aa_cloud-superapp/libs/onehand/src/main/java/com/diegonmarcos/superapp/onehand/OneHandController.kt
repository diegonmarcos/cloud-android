package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The single entry point app/ (a Configs/Onehand toggle) calls. Keeps all the
 * permission plumbing here so the UI side is just enable()/disable()/status().
 * Both special permissions are user-granted in system Settings — we can only
 * deep-link, never grant.
 */
object OneHandController {

    fun canDrawOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun accessibilityEnabled(): Boolean = OneHandAccessibilityService.isEnabled

    /** True only once both prerequisites are satisfied. */
    fun ready(ctx: Context): Boolean = canDrawOverlay(ctx) && accessibilityEnabled()

    fun enable(ctx: Context) { if (ready(ctx)) EdgeOverlayService.start(ctx) }

    fun disable(ctx: Context) { EdgeOverlayService.stop(ctx) }

    /** Settings > Draw over other apps, focused on this app. */
    fun overlaySettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    /** Settings > Accessibility (the OS gives no per-app deep link). */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
