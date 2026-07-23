package com.diegonmarcos.ide

import android.util.Base64
import org.json.JSONObject

/**
 * Read-only accessor over `data/terminal-targets.json`, baked into
 * BuildConfig.TERMINAL_TARGETS_JSON_B64 at build time — no hardcoded
 * host/port/user in Kotlin (mirrors Endpoints.kt / FIRE rule 4).
 */
object TerminalTargets {

    /** An SSH target descriptor from terminal-targets.json::backends. */
    data class Target(
        val key:   String,
        val label: String,
        val host:  String,
        val port:  Int,
        val user:  String,
    )

    private val root: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.TERMINAL_TARGETS_JSON_B64, Base64.DEFAULT)))
    }

    /**
     * Returns the [Target] for [key] (e.g. "termux"), falling back to the
     * "termux" entry, then to the first available backend, then throwing if
     * the JSON is empty.
     */
    fun forBackend(key: String): Target {
        val backends = root.getJSONObject("backends")
        // Prefer exact key, then "termux", then first available.
        val resolvedKey = when {
            backends.has(key)               -> key
            backends.has(IdePrefs.BACKEND_TERMUX) -> IdePrefs.BACKEND_TERMUX
            else                            -> backends.keys().next()
        }
        val obj = backends.getJSONObject(resolvedKey)
        return Target(
            key   = resolvedKey,
            label = obj.getString("label"),
            host  = obj.getString("host"),
            port  = obj.getInt("port"),
            user  = obj.getString("user"),
        )
    }
}
