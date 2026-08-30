package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/**
 * Per-device user overrides for the label / host / port. The build.json
 * devices[] entry is the DEFAULT (first value); the Edit button persists an
 * override here keyed by the config device id. Nothing in build.json is
 * mutated — the override layers on top at runtime.
 */
class KdeDevicePrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun label(id: String, default: String): String = sp.getString("$id.label", null) ?: default
    fun host(id: String, default: String): String = sp.getString("$id.host", null) ?: default
    fun port(id: String, default: Int): Int = sp.getInt("$id.port", default)

    fun save(id: String, label: String, host: String, port: Int) {
        sp.edit()
            .putString("$id.label", label)
            .putString("$id.host", host)
            .putInt("$id.port", port)
            .apply()
    }

    companion object { private const val PREFS = "kdeconnect_device_overrides" }
}
