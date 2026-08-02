package com.diegonmarcos.cloudnav.maps

import android.content.Context

/**
 * Per-layer visual settings, controlled from Configs > Layers. Native
 * source of truth (SharedPreferences) for values that a WebView-hosted
 * screen (e.g. [com.diegonmarcos.cloudnav.TerrainActivity]) also exposes
 * its own in-page control for — the native Layers tab is authoritative;
 * TerrainActivity reads the current value on launch and pushes it into the
 * page via its `AndroidBridge` JS interface, and the page's own slider
 * writes back through that same bridge so both stay in sync.
 */
class MapsLayerPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_layer_prefs", Context.MODE_PRIVATE)

    /** Terrain view's vertical exaggeration, 1x-20x, whole numbers only. */
    var terrainExaggeration: Int
        get() = sp.getInt(KEY_TERRAIN_EXAGGERATION, DEFAULT_TERRAIN_EXAGGERATION).coerceIn(1, 20)
        set(value) { sp.edit().putInt(KEY_TERRAIN_EXAGGERATION, value.coerceIn(1, 20)).apply() }

    private companion object {
        const val KEY_TERRAIN_EXAGGERATION = "terrain_exaggeration"
        const val DEFAULT_TERRAIN_EXAGGERATION = 3
    }
}
