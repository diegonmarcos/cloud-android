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
}
