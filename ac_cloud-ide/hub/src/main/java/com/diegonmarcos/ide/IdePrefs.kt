package com.diegonmarcos.ide

import android.content.Context

/**
 * Hub-wide SharedPreferences accessors — one place for every persisted toggle
 * so Kotlin callers never hardcode pref keys (mirrors AutoUpdatePrefs pattern).
 * All defaults come from BuildConfig (baked from build.json) so the source of
 * truth is the data file, not scattered Kotlin constants.
 */
object IdePrefs {
    private const val PREFS = "ide_prefs"

    // ── Terminal backend ──────────────────────────────────────────────────────
    /** Key for the JSON [backends] map (matches terminal-targets.json). */
    const val BACKEND_TERMUX      = "termux"
    const val BACKEND_NIXONDROID  = "nix-on-droid"
    private const val KEY_BACKEND = "terminal_backend"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns the active terminal backend key ("termux" or "nix-on-droid"). */
    fun terminalBackend(ctx: Context): String =
        sp(ctx).getString(KEY_BACKEND, BuildConfig.TERMINAL_BACKEND_DEFAULT)
            ?: BuildConfig.TERMINAL_BACKEND_DEFAULT

    fun setTerminalBackend(ctx: Context, v: String) {
        sp(ctx).edit().putString(KEY_BACKEND, v).apply()
    }

    // ── Per-backend connection overrides ──────────────────────────────────────
    // Keys: terminal_<backend>_host / _port / _user
    // Getters return null / -1 / null when unset (caller uses baked default).

    fun terminalHost(ctx: Context, backend: String): String? =
        sp(ctx).getString("terminal_${backend}_host", null)

    fun terminalPort(ctx: Context, backend: String): Int =
        sp(ctx).getInt("terminal_${backend}_port", -1)

    fun terminalUser(ctx: Context, backend: String): String? =
        sp(ctx).getString("terminal_${backend}_user", null)

    /** Persist all three connection fields for [backend]. */
    fun setTerminalConn(ctx: Context, backend: String, host: String, port: Int, user: String) {
        sp(ctx).edit()
            .putString("terminal_${backend}_host", host)
            .putInt("terminal_${backend}_port", port)
            .putString("terminal_${backend}_user", user)
            .apply()
    }

    // ── Dev API toggle ────────────────────────────────────────────────────────
    private const val KEY_DEV_API = "dev_api_enabled"

    fun devApiEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DEV_API, false)

    fun setDevApiEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DEV_API, v).apply()
    }

    /** Remove all connection overrides for [backend], reverting to baked defaults. */
    fun clearTerminalConn(ctx: Context, backend: String) {
        sp(ctx).edit()
            .remove("terminal_${backend}_host")
            .remove("terminal_${backend}_port")
            .remove("terminal_${backend}_user")
            .apply()
    }
}
