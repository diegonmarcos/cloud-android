package com.diegonmarcos.superapp.floatingnav

import android.content.Context

/**
 * Runtime on/off for the floating button, persisted on-device.
 *
 * [FloatingNavConfig] already had an `enabled` flag, but it comes from
 * build.json and is therefore a BUILD-time decision — the user could not turn
 * the overlay off without a rebuild, and it restarted itself every time
 * MainActivity opened. This is the switch that was missing.
 *
 * The default is whatever build.json says, so a device that never touches the
 * toggle behaves exactly as before; the pref only exists once someone has an
 * opinion.
 *
 * Deliberately app-side rather than in libs:launcher-onehand: the edge handles
 * and this overlay are two different services with two different permissions,
 * and giving the shared library a key it does not own would tie them together
 * for no reason beyond sharing a settings screen.
 */
object FloatingNavPrefs {

    private const val FILE = "floatingnav_prefs"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Build.json's value until the user overrides it. */
    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, FloatingNavConfig.get().enabled)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
