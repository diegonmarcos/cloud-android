package com.diegonmarcos.superapp

import android.content.Context

/**
 * Active provider selection per slot (reverse-geocode + POI). Stored in
 * SharedPreferences so the picker change persists across app restarts.
 * First-read defaults to the build.json-baked
 * [BuildConfig.UI_MAPS_ACTIVE_REVERSE] / `UI_MAPS_ACTIVE_POI`.
 */
class MapsProviderPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_provider_prefs", Context.MODE_PRIVATE)

    var activeReverse: String
        get() = sp.getString(KEY_REVERSE, BuildConfig.UI_MAPS_ACTIVE_REVERSE) ?: BuildConfig.UI_MAPS_ACTIVE_REVERSE
        set(value) { sp.edit().putString(KEY_REVERSE, value).apply() }

    var activePoi: String
        get() = sp.getString(KEY_POI, BuildConfig.UI_MAPS_ACTIVE_POI) ?: BuildConfig.UI_MAPS_ACTIVE_POI
        set(value) { sp.edit().putString(KEY_POI, value).apply() }

    fun activeFor(kind: String): String = when (kind) {
        "reverse" -> activeReverse
        "poi"     -> activePoi
        else      -> ""
    }

    fun setActiveFor(kind: String, id: String) { when (kind) {
        "reverse" -> activeReverse = id
        "poi"     -> activePoi = id
    } }

    companion object {
        private const val KEY_REVERSE = "active_reverse"
        private const val KEY_POI     = "active_poi"
    }
}
