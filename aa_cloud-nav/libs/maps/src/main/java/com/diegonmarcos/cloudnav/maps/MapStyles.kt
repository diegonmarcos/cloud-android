package com.diegonmarcos.cloudnav.maps

import android.util.Base64
import org.json.JSONObject

/**
 * Data-driven basemap styles, decoded from build.json::ui.map_styles (baked
 * into BuildConfig.UI_MAP_STYLES_B64). Three kinds of entry, told apart by
 * which optional fields are set:
 *   • raster   — [vectorStyleUrl]/[hybrid] both unset. Built inline from
 *                tiles/attribution/background/maxzoom (no remote style.json).
 *   • vector   — [vectorStyleUrl] set, [hybrid] false. A real GL style fetched
 *                + localized by [VectorStyleLoader].
 *   • hybrid   — [hybrid] true. Raster imagery ([baseStyleKey]'s tiles) with an
 *                English roads+labels-only vector overlay ([vectorOverlayUrl])
 *                composited on top by [VectorStyleLoader.buildHybrid] — for
 *                "satellite", which has no vector form of its own (imagery
 *                isn't vector geometry).
 * [order] is every entry, in the cycle the in-map Map-switcher FAB steps
 * through AND the full picker in Configs > APIs > Basemap. No hardcoded tile
 * URLs in Kotlin (FIRE RULE #6) — all of it is JSON.
 */
object MapStyles {

    data class Style(
        val key: String,
        val label: String,
        val tiles: String,
        val attribution: String,
        val background: String,
        val maxzoom: Int,
        val glyphs: String,
        /** Raster entries only: the vector/hybrid style [MapsBasemapPrefs.resolve]
         *  substitutes when the vector family is preferred. */
        val vectorEquivalent: String? = null,
        /** Non-null → a vector GL style ([VectorStyleLoader] fetches + localizes it). */
        val vectorStyleUrl: String? = null,
        /** Preferred label tag for vector/hybrid styles, e.g. "name_en". */
        val labelFieldPref: String = "name_en",
        /** True → this is a raster+vector hybrid (see [baseStyleKey]/[vectorOverlayUrl]). */
        val hybrid: Boolean = false,
        /** Hybrid only: the raster style whose imagery is the base layer. */
        val baseStyleKey: String? = null,
        /** Hybrid only: vector style fetched for its roads+labels overlay. */
        val vectorOverlayUrl: String? = null,
        /** Vector/hybrid only: raster style to fall back to if the network fetch fails. */
        val rasterFallback: String? = null,
    )

    /** Glyph (font PBF) endpoint for SymbolLayer text — data-driven, one for all
     *  raster styles (build.json::ui.map_styles.glyphs). Vector/hybrid styles use
     *  the glyphs endpoint from their own fetched style instead. */
    private val glyphs: String by lazy {
        root.optString("glyphs", "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf")
    }

    /** Default basemap family ("vector" | "raster") — [MapsBasemapPrefs]' first-run
     *  value; user-overridable (family default, or a pinned concrete style) in
     *  Configs > APIs. */
    val defaultFamily: String by lazy { root.optString("default_family", "vector") }

    /** Real-elevation hillshade config (build.json::ui.map_styles.hillshade) —
     *  a raster-DEM source + hillshade layer [VectorStyleLoader] adds to every
     *  vector style. Null when unconfigured (feature off). Not a selectable
     *  basemap (absent from [order]); 2D shading only — MapLibre Native has no
     *  3D tilted terrain (maplibre-native#252). */
    data class Hillshade(
        val demTiles: String,
        val encoding: String,
        val tileSize: Int,
        val maxzoom: Int,
        val exaggeration: Double,
        val attribution: String,
    )

    val hillshade: Hillshade? by lazy {
        val o = root.optJSONObject("hillshade") ?: return@lazy null
        val tiles = o.optString("dem_tiles", "").ifBlank { return@lazy null }
        Hillshade(
            demTiles = tiles,
            encoding = o.optString("encoding", "terrarium"),
            tileSize = o.optInt("tile_size", 256),
            maxzoom = o.optInt("maxzoom", 15),
            exaggeration = o.optDouble("exaggeration", 0.45),
            attribution = o.optString("attribution", ""),
        )
    }

    private val root: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.UI_MAP_STYLES_B64, Base64.DEFAULT))) }
            .getOrDefault(JSONObject())
    }

    /** Every style key, in FAB-cycle / Configs-menu order; falls back to [light] if absent. */
    val order: List<String> by lazy {
        val arr = root.optJSONArray("order")
        if (arr == null || arr.length() == 0) listOf("light")
        else (0 until arr.length()).map { arr.getString(it) }
    }

    fun get(key: String): Style {
        val o = root.optJSONObject(key) ?: JSONObject()
        return Style(
            key = key,
            label = o.optString("label", key.replaceFirstChar { it.uppercase() }),
            tiles = o.optString("tiles", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
            attribution = o.optString("attribution", "© OpenStreetMap contributors"),
            background = o.optString("background", "#e8eef4"),
            maxzoom = o.optInt("maxzoom", 19),
            glyphs = glyphs,
            vectorEquivalent = o.optString("vector_equivalent", "").ifBlank { null },
            vectorStyleUrl = o.optString("vector_style_url", "").ifBlank { null },
            labelFieldPref = o.optString("label_field_pref", "name_en"),
            hybrid = o.optBoolean("hybrid", false),
            baseStyleKey = o.optString("base_style", "").ifBlank { null },
            vectorOverlayUrl = o.optString("vector_overlay_url", "").ifBlank { null },
            rasterFallback = o.optString("raster_fallback", "").ifBlank { null },
        )
    }

    /** Next key after [current] in the cycle (wraps). */
    fun next(current: String): String {
        val i = order.indexOf(current)
        return order[(if (i < 0) 0 else i + 1) % order.size]
    }

    /** Pure resolution: [key] as-is when raster is preferred (or it has no
     *  vector form); its [Style.vectorEquivalent] when vector is preferred. */
    fun resolve(key: String, preferVector: Boolean): String {
        if (!preferVector) return key
        return get(key).vectorEquivalent ?: key
    }

    /** Inline MapLibre raster style JSON for [s]. */
    fun styleJson(s: Style): String = """
        {
          "version": 8,
          "name": "cloudnav-${s.key}",
          "glyphs": "${s.glyphs}",
          "sources": {
            "raster": {
              "type": "raster",
              "tiles": ["${s.tiles}"],
              "tileSize": 256,
              "attribution": "${s.attribution}",
              "maxzoom": ${s.maxzoom}
            }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "${s.background}" } },
            { "id": "raster",     "type": "raster",     "source": "raster" }
          ]
        }
    """.trimIndent()
}
