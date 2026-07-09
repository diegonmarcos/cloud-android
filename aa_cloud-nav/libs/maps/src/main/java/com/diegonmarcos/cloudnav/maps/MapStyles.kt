package com.diegonmarcos.cloudnav.maps

import android.util.Base64
import org.json.JSONObject

/**
 * Data-driven raster basemap styles, decoded from build.json::ui.map_styles
 * (baked into BuildConfig.UI_MAP_STYLES_B64). [order] is the cycle the in-map
 * Map-switcher FAB steps through. Each [Style] builds its own MapLibre raster
 * style JSON inline — no remote style.json, only the tile server. No hardcoded
 * tile URLs in Kotlin (FIRE RULE #6).
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
        /** Non-null → a vector GL style ([VectorStyleLoader] fetches + localizes it);
         *  raster fields above are unused for this style. */
        val vectorStyleUrl: String? = null,
        /** Preferred label tag for vector styles, e.g. "name:en". */
        val labelFieldPref: String = "name:en",
    )

    /** Glyph (font PBF) endpoint for SymbolLayer text — data-driven, one for all
     *  styles (build.json::ui.map_styles.glyphs). Without it label text can't
     *  rasterise; the public MapLibre demotiles font server is the default. */
    private val glyphs: String by lazy {
        root.optString("glyphs", "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf")
    }

    private val root: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.UI_MAP_STYLES_B64, Base64.DEFAULT))) }
            .getOrDefault(JSONObject())
    }

    /** Ordered style keys for the switcher; falls back to [light] if absent. */
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
            vectorStyleUrl = o.optString("vector_style_url", "").ifBlank { null },
            labelFieldPref = o.optString("label_field_pref", "name:en"),
        )
    }

    /** Next key after [current] in the cycle (wraps). */
    fun next(current: String): String {
        val i = order.indexOf(current)
        return order[(if (i < 0) 0 else i + 1) % order.size]
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
