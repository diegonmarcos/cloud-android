package com.diegonmarcos.superapp.firewall

import android.content.Context

/**
 * Persistence for the firewall's per-app block list and on/off state.
 *
 * The blocked-app set is USER DATA (a runtime-mutable set of package
 * names), so it lives in SharedPreferences — never hardcoded in source.
 * The engine and the UI both read/write through here, so there is a
 * single source of truth on disk.
 */
object FirewallPrefs {
    private const val FILE = "firewall"
    private const val KEY_BLOCKED = "blocked_packages" // Set<String> of pkgs denied internet
    private const val KEY_ENABLED = "enabled"          // user's desired firewall on/off

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Packages the user has blocked from the network. Returns a copy. */
    fun blocked(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun isBlocked(ctx: Context, pkg: String): Boolean = blocked(ctx).contains(pkg)

    /** Add or remove a package from the block set. */
    fun setBlocked(ctx: Context, pkg: String, blocked: Boolean) {
        val cur = blocked(ctx).toMutableSet()
        if (blocked) cur.add(pkg) else cur.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_BLOCKED, cur).apply()
    }

    /** The user's desired firewall state — independent of whether the VPN
     *  is actually established at this instant. */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
