package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/**
 * Per-plugin enable/disable, persisted. A disabled plugin is neither advertised
 * (dropped from our identity capabilities) nor dispatched. Default = enabled.
 * Keyed by the plugin/catalog id.
 */
class KdePluginPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(id: String): Boolean = sp.getBoolean(id, true)
    fun setEnabled(id: String, on: Boolean) { sp.edit().putBoolean(id, on).apply() }
    fun toggle(id: String): Boolean = (!isEnabled(id)).also { setEnabled(id, it) }
    companion object { private const val PREFS = "kdeconnect_plugin_enabled" }
}
