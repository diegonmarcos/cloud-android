package com.diegonmarcos.ide

import android.content.Context

/**
 * Tiny persisted-preferences accessor for the hub. Defaults come from
 * BuildConfig (baked from build.json::ui) so the shipped default stays
 * data-driven; the user's choice overrides it in SharedPreferences.
 */
object IdePrefs {
    private const val FILE = "cloud_ide_prefs"
    private const val KEY_OVERLAY_NAVBAR = "overlay_nav_bar"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Floating cross-app wrapper bar (NavOverlayService). Default OFF
     *  (build.json::ui.overlay_nav_bar_default) — SuperApp owns the system bar. */
    fun overlayNavBar(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_OVERLAY_NAVBAR, BuildConfig.OVERLAY_NAVBAR_DEFAULT)

    fun setOverlayNavBar(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_OVERLAY_NAVBAR, enabled).apply()
    }
}
