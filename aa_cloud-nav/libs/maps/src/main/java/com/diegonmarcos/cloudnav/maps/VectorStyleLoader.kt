package com.diegonmarcos.cloudnav.maps

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches a public vector-tile GL style (OpenFreeMap: "positron"/"dark"/…) and
 * rewrites its label layers to prefer an English name field, so city/country/
 * street labels render in English instead of each place's local script. Also
 * builds the "satellite (labels)" hybrid: raster satellite imagery with just
 * that vector style's roads + name labels composited on top (fills/water/
 * landuse polygons dropped — imagery already shows those; icon-only symbol
 * layers dropped — labels only, no shield/POI icon clutter).
 *
 * OpenMapTiles-schema styles (confirmed against tiles.openfreemap.org/styles/
 * {liberty,positron,dark}) express every name label as a `case`/`coalesce`
 * expression built on `name`, `name:latin`, `name:nonlatin`, `name_en` — never
 * a bare string — so we detect "is this a name label" by checking the
 * serialized expression for the substring "name" and leave everything else
 * (road-ref shields use `ref`, not `name`) untouched. Network + JSON parsing,
 * so call off the main thread.
 */
object VectorStyleLoader {

    /** Returns the localized style JSON, or null on any network/parse failure
     *  (caller falls back / toasts — never crashes the map). */
    fun loadLocalized(styleUrl: String, preferField: String): String? = runCatching {
        val body = fetch(styleUrl) ?: return null
        localize(body, preferField)
    }.getOrNull()

    /** Pure JSON rewrite (no network) — every name-label symbol layer gets a
     *  `["coalesce", ["get", preferField], ["get","name:latin"], ["get","name"]]`
     *  text-field. Non-name labels (road-ref shields) are left untouched.
     *  Exposed for testing without a network round-trip. */
    fun localize(styleJson: String, preferField: String): String {
        val root = JSONObject(styleJson)
        val layers = root.optJSONArray("layers") ?: return root.toString()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.optString("type") != "symbol") continue
            val layout = layer.optJSONObject("layout") ?: continue
            if (!isNameLabel(layout)) continue
            localizeLayout(layout, preferField)
        }
        return root.toString()
    }

    /** Fetches [vectorOverlayUrl] and composites its roads+labels over
     *  [base]'s raster imagery. Null on any network/parse failure. */
    fun buildHybrid(base: MapStyles.Style, vectorOverlayUrl: String, preferField: String): String? = runCatching {
        val body = fetch(vectorOverlayUrl) ?: return null
        buildHybridFromJson(body, base, preferField)
    }.getOrNull()

    /** Pure composite (no network) — exposed for testing without a network
     *  round-trip. Keeps only [line] and name-bearing [symbol] layers from the
     *  fetched vector style (drops background/fill/hillshade-raster layers and
     *  the non-vector source they reference), recoloring both for legibility
     *  over arbitrary imagery, and stacks them over a raster source built from
     *  [base]'s tiles/attribution/maxzoom. */
    fun buildHybridFromJson(vectorStyleJson: String, base: MapStyles.Style, preferField: String): String {
        val vec = JSONObject(vectorStyleJson)
        val vecSources = vec.optJSONObject("sources") ?: JSONObject()

        val sources = JSONObject().put(
            "raster",
            JSONObject().put("type", "raster")
                .put("tiles", JSONArray().put(base.tiles))
                .put("tileSize", 256)
                .put("attribution", base.attribution)
                .put("maxzoom", base.maxzoom),
        )
        // Keep only the vector-type source(s) (e.g. "openmaptiles") — skip any
        // raster hillshade/relief source the fetched style also declares.
        val vectorSourceIds = HashSet<String>()
        vecSources.keys().forEach { k ->
            val src = vecSources.optJSONObject(k) ?: return@forEach
            if (src.optString("type") == "vector") { sources.put(k, src); vectorSourceIds += k }
        }

        val layers = JSONArray().put(JSONObject().put("id", "hybrid-raster").put("type", "raster").put("source", "raster"))
        val vecLayers = vec.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until vecLayers.length()) {
            val l = vecLayers.getJSONObject(i)
            if (l.optString("source") !in vectorSourceIds) continue  // e.g. background / hillshade-raster
            when (l.optString("type")) {
                "line" -> { recolorLine(l); layers.put(l) }
                "symbol" -> {
                    val layout = l.optJSONObject("layout") ?: continue
                    if (!isNameLabel(layout)) continue  // icon-only POI/shield — labels only, per spec
                    localizeLayout(layout, preferField)
                    layout.remove("icon-image")
                    recolorSymbol(l)
                    layers.put(l)
                }
                else -> {} // fill / fill-extrusion / background — imagery already shows these
            }
        }

        return JSONObject()
            .put("version", 8)
            .put("name", "cloudnav-satellite-hybrid")
            .put("glyphs", vec.optString("glyphs").ifBlank { "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf" })
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun isNameLabel(layout: JSONObject): Boolean {
        val field = layout.opt("text-field") ?: return false
        return field.toString().contains("name")
    }

    private fun localizeLayout(layout: JSONObject, preferField: String) {
        layout.put(
            "text-field",
            JSONArray().put("coalesce")
                .put(JSONArray().put("get").put(preferField))
                .put(JSONArray().put("get").put("name:latin"))
                .put(JSONArray().put("get").put("name")),
        )
    }

    /** White, semi-opaque — reads against dark or light imagery alike. */
    private fun recolorLine(layer: JSONObject) {
        val paint = layer.optJSONObject("paint") ?: JSONObject().also { layer.put("paint", it) }
        paint.put("line-color", "#FFFFFF")
        paint.put("line-opacity", 0.85)
    }

    /** White text, black halo — legible on any photographic background. */
    private fun recolorSymbol(layer: JSONObject) {
        val paint = layer.optJSONObject("paint") ?: JSONObject().also { layer.put("paint", it) }
        paint.put("text-color", "#FFFFFF")
        paint.put("text-halo-color", "#000000")
        paint.put("text-halo-width", 1.4)
    }

    private fun fetch(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "CloudNav/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()
}
