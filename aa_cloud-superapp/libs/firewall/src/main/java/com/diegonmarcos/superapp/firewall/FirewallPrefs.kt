package com.diegonmarcos.superapp.firewall

import android.content.Context

/**
 * Persistence for the firewall's master on/off (desired) state only.
 *
 * The per-app block rules live in [FirewallRules] (single source of truth);
 * this file holds nothing but the user's desired firewall on/off flag, kept
 * separate from whether the VpnService is actually established right now.
 */
object FirewallPrefs {
    private const val FILE = "firewall"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
