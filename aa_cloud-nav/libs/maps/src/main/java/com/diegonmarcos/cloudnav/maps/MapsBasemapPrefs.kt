package com.diegonmarcos.cloudnav.maps

import android.content.Context

/**
 * Persisted basemap family choice (Configs > APIs > "Basemap"): vector
 * (English labels, on-device rendering) or raster (fixed local-language tile
 * images). First-read defaults to build.json-baked [MapStyles.defaultFamily].
 * Only affects screens that don't force a specific style (e.g. Places'
 * satellite, cockpit modes' per-vehicle style) — see [MapsMapFragment].
 */
class MapsBasemapPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_basemap_prefs", Context.MODE_PRIVATE)

    var preferVector: Boolean
        get() = sp.getString(KEY_FAMILY, null)?.let { it == "vector" } ?: (MapStyles.defaultFamily == "vector")
        set(value) { sp.edit().putString(KEY_FAMILY, if (value) "vector" else "raster").apply() }

    private companion object { const val KEY_FAMILY = "family" }
}
