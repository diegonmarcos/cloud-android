package com.diegonmarcos.cloudnav.maps

import android.content.Context

/**
 * Persisted basemap choice (Configs > APIs > "Basemap" menu). Two levels:
 *   • [explicitStyleKey] — a concrete style pinned by the user (any
 *     [MapStyles.order] key); wins everywhere, overriding every screen's
 *     smart per-screen default (Navigation's dark, Places' satellite, …).
 *   • [preferVectorFamily] — used only when nothing is pinned ("Auto"):
 *     each screen's own raster style resolves through [MapStyles.resolve] to
 *     its vector/hybrid form. First-read defaults to build.json-baked
 *     [MapStyles.defaultFamily].
 */
class MapsBasemapPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_basemap_prefs", Context.MODE_PRIVATE)

    var explicitStyleKey: String?
        get() = sp.getString(KEY_EXPLICIT, null)
        set(value) { sp.edit().putString(KEY_EXPLICIT, value).apply() }

    var preferVectorFamily: Boolean
        get() = sp.getString(KEY_FAMILY, null)?.let { it == "vector" } ?: (MapStyles.defaultFamily == "vector")
        set(value) { sp.edit().putString(KEY_FAMILY, if (value) "vector" else "raster").apply() }

    /** Resolve a screen's own raw style key ("light"/"dark"/"satellite"/…)
     *  against the user's basemap choice: an explicit pin wins outright,
     *  otherwise [MapStyles.resolve] applies the family preference. */
    fun resolve(rawKey: String): String = explicitStyleKey ?: MapStyles.resolve(rawKey, preferVectorFamily)

    private companion object {
        const val KEY_EXPLICIT = "explicit_style_key"
        const val KEY_FAMILY = "family"
    }
}
