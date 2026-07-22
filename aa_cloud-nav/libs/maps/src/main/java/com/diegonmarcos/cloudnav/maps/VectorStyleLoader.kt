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

    /** Source id for the injected raster-DEM (guards idempotent re-injection). */
    private const val DEM_SOURCE = "cloudnav-dem"

    /** Returns the localized style JSON, or null on any network/parse failure
     *  (caller falls back / toasts — never crashes the map). [hillshade], when
     *  non-null, adds a real-elevation raster-DEM + hillshade layer. */
    fun loadLocalized(styleUrl: String, preferField: String, hillshade: MapStyles.Hillshade? = null): String? = runCatching {
        val body = fetch(styleUrl) ?: return null
        localize(body, preferField, hillshade)
    }.getOrNull()

    /** Pure JSON rewrite (no network) — every name-label symbol layer gets a
     *  `["coalesce", ["get", preferField], ["get","name:latin"], ["get","name"]]`
     *  text-field. Non-name labels (road-ref shields) are left untouched.
     *  Exposed for testing without a network round-trip. */
    fun localize(styleJson: String, preferField: String, hillshade: MapStyles.Hillshade? = null): String {
        val root = JSONObject(styleJson)
        val layers = root.optJSONArray("layers") ?: return root.toString()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.optString("type") != "symbol") continue
            val layout = layer.optJSONObject("layout") ?: continue
            if (!isNameLabel(layout)) continue
            localizeLayout(layout, preferField)
        }
        injectBuildings3d(root)
        if (hillshade != null) injectHillshade(root, hillshade)
        return root.toString()
    }

    /**
     * Adds a real-elevation raster-DEM source + a `hillshade` layer, replacing
     * the flat painted relief the style ships (positron/dark's `ne2_shaded`
     * image) with shading computed from actual elevation. MapLibre Native
     * renders hillshade natively; this is 2D shading — it does NOT rise when the
     * camera tilts (true 3D terrain is unsupported by MapLibre Native — see
     * maplibre-native#252). Idempotent; the DEM source id guards re-entry.
     *
     * The hillshade layer is inserted just below the first vector line/symbol
     * (roads/labels) so relief reads under them but over the base fills —
     * positioned by layer TYPE, never a hardcoded index.
     */
    private fun injectHillshade(root: JSONObject, h: MapStyles.Hillshade) {
        val sources = root.optJSONObject("sources") ?: JSONObject().also { root.put("sources", it) }
        if (sources.has(DEM_SOURCE)) return  // already added
        sources.put(
            DEM_SOURCE,
            JSONObject()
                .put("type", "raster-dem")
                .put("tiles", JSONArray().put(h.demTiles))
                .put("tileSize", h.tileSize)
                .put("encoding", h.encoding)
                .put("maxzoom", h.maxzoom)
                .put("attribution", h.attribution),
        )
        val layers = root.optJSONArray("layers") ?: JSONArray().also { root.put("layers", it) }
        val hillshadeLayer = JSONObject()
            .put("id", "cloudnav-hillshade")
            .put("type", "hillshade")
            .put("source", DEM_SOURCE)
            .put("paint", JSONObject().put("hillshade-exaggeration", h.exaggeration))
        // Insert just before the first road (line) — else first label (symbol),
        // else after the base layer — so relief sits over fills, under roads.
        var insertAt = -1
        for (i in 0 until layers.length()) {
            if (layers.getJSONObject(i).optString("type") == "line") { insertAt = i; break }
        }
        if (insertAt < 0) for (i in 0 until layers.length()) {
            if (layers.getJSONObject(i).optString("type") == "symbol") { insertAt = i; break }
        }
        if (insertAt < 0) insertAt = minOf(1, layers.length())
        val rebuilt = JSONArray()
        for (i in 0 until layers.length()) {
            if (i == insertAt) rebuilt.put(hillshadeLayer)
            rebuilt.put(layers.getJSONObject(i))
        }
        if (insertAt >= layers.length()) rebuilt.put(hillshadeLayer)
        root.put("layers", rebuilt)
    }

    /**
     * OpenFreeMap's positron/dark styles carry the OpenMapTiles `building`
     * source-layer (real footprints + `render_height`/`render_min_height`) but
     * render it as a FLAT 2D fill — no `fill-extrusion` layer — so a tilted
     * camera shows a flat map, never 3D buildings. If the style has a building
     * fill but no extrusion, synthesize one from the SAME source/source-layer,
     * reusing the 2D fill's own colour so it matches the light/dark basemap.
     *
     * Idempotent and safe: a no-op when the style already declares ANY
     * fill-extrusion (e.g. liberty's `building-3d`) or has no building layer.
     * The extrusion only visibly "pops" when the camera is tilted — flat maps
     * see the roof top-down, indistinguishable from the old 2D fill.
     */
    private fun injectBuildings3d(root: JSONObject) {
        val layers = root.optJSONArray("layers") ?: return
        var buildingFillIdx = -1
        var buildingSource: String? = null
        var buildingColor = "#c8ccd4"
        var buildingMinzoom = 13  // floor when the fill declares none
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.optString("type") == "fill-extrusion") return  // already 3D — leave it
            if (buildingFillIdx < 0 && l.optString("type") == "fill" &&
                l.optString("source-layer") == "building"
            ) {
                buildingFillIdx = i
                buildingSource = l.optString("source").ifBlank { null }
                (l.optJSONObject("paint")?.opt("fill-color") as? String)
                    ?.takeIf { it.isNotBlank() }?.let { buildingColor = it }
                // Match the 2D fill's own zoom floor so the 3D buildings appear
                // at the SAME zoom the flat footprints do (positron/dark = 12),
                // instead of only when zoomed way in.
                buildingMinzoom = l.optInt("minzoom", buildingMinzoom)
            }
        }
        val src = buildingSource ?: return  // no building geometry to extrude
        val extrusion = JSONObject()
            .put("id", "cloudnav-building-3d")
            .put("type", "fill-extrusion")
            .put("source", src)
            .put("source-layer", "building")
            .put("minzoom", buildingMinzoom)
            .put(
                "paint",
                JSONObject()
                    .put("fill-extrusion-color", buildingColor)
                    .put("fill-extrusion-height", JSONArray().put("coalesce").put(JSONArray().put("get").put("render_height")).put(5))
                    .put("fill-extrusion-base", JSONArray().put("coalesce").put(JSONArray().put("get").put("render_min_height")).put(0))
                    .put("fill-extrusion-opacity", 0.85),
            )
        // Rebuild the array with the extrusion inserted right after the 2D
        // building fill (org.json JSONArray has no insert-at-index) — keeps it in
        // buildings' paint slot, below the labels/roads that follow.
        val rebuilt = JSONArray()
        for (i in 0 until layers.length()) {
            rebuilt.put(layers.getJSONObject(i))
            if (i == buildingFillIdx) rebuilt.put(extrusion)
        }
        root.put("layers", rebuilt)
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
