package com.diegonmarcos.cloudnav.maps

import android.content.Context
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic HTTP wrapper that resolves a coordinate against whichever
 * reverse-geocode provider is active in [MapsProviderPrefs]. Reads
 * the URL template + auth requirement from [MapsProviders] (data-
 * driven from build.json::ui.maps_providers), substitutes
 * `{lat}`/`{lon}`/`{key}`, fires a single HttpURLConnection, parses
 * the response per provider id.
 *
 * No OkHttp / Retrofit dependency added — plain stdlib keeps the
 * APK lean and avoids new transitive conflicts. ~50ms typical latency
 * per call against the public Nominatim/Photon endpoints, fair-use
 * compliant for one-user / one-Stop-per-few-minutes traffic.
 *
 * Caller responsibility:
 *   • Call OFF the main thread ([StopsEnricher] uses Thread{}).
 *   • Respect the 1 req/sec Nominatim fair-use ceiling — the enricher
 *     sleeps 1100ms between calls to be safe.
 *   • Pass a sane User-Agent — Nominatim REQUIRES it (returns 403
 *     otherwise); we send "CloudSuperApp/${BuildConfig.VERSION_NAME}".
 */
object MapsProviderClient {

    /** Reverse-geocode (lat,lon) → place names. Returns null on any
     *  network / parse failure; caller writes back enriched_at=now
     *  anyway so failed Stops aren't infinitely retried. */
    fun reverseGeocode(ctx: Context, lat: Double, lon: Double): ReverseResult? {
        val prefs = MapsProviderPrefs(ctx)
        val keys = MapsApiKeyPrefs(ctx)
        val all = MapsProviders.loadFromBuildConfig()
        val active = all.firstOrNull { it.id == prefs.activeReverse } ?: return null
        val url = active.endpoint
            .replace("{lat}", lat.toString())
            .replace("{lon}", lon.toString())
            .replace("{key}", keys.get(active.id))
        val body = fetch(url) ?: return null
        return parse(active.id, body)
    }

    /**
     * Forward-geocode (search): free text → ranked list of places. Powers
     * the universal search bar — "Berlin", "Tokyo", "Café near me",
     * "Brandenburg Gate", a neighborhood, a country, anything. Hits the
     * ACTIVE search provider ([MapsProviderPrefs.activeSearch], data-driven
     * from build.json::ui.maps_active.search). [focusLat]/[focusLon] bias the
     * ranking toward the user's current map centre when the provider supports
     * it (Photon always, Nominatim ignores them harmlessly).
     *
     * MUST be called off the main thread (NetworkOnMainThreadException
     * otherwise). Returns an empty list on any network/parse failure.
     */
    fun forwardSearch(
        ctx: Context,
        query: String,
        focusLat: Double = 0.0,
        focusLon: Double = 0.0,
        limit: Int = 25,
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val prefs = MapsProviderPrefs(ctx)
        val keys = MapsApiKeyPrefs(ctx)
        val all = MapsProviders.loadFromBuildConfig()
        val active = all.firstOrNull { it.id == prefs.activeSearch && it.searchEndpoint.isNotEmpty() }
            ?: all.firstOrNull { it.kinds.contains("search") && it.searchEndpoint.isNotEmpty() }
            ?: return emptyList()
        val url = active.searchEndpoint
            .replace("{q}", URLEncoder.encode(query, "UTF-8"))
            .replace("{lat}", focusLat.toString())
            .replace("{lon}", focusLon.toString())
            .replace("{key}", keys.get(active.id))
        val body = fetch(url) ?: return emptyList()
        return parseSearch(active.id, body).take(limit)
    }

    /**
     * POI lookup: every place of a given category ([tagQuery] like
     * `amenity=bar`, `shop=supermarket`, `tourism=hotel`) inside the current
     * map viewport bounding box. Uses Overpass (the ACTIVE poi provider's
     * interpreter endpoint, POST body = Overpass QL). Returns nodes + the
     * centroid of ways/relations so big venues (a mall, a park) still pin.
     *
     * MUST be called off the main thread. Empty list on failure.
     */
    fun poiSearch(
        ctx: Context,
        tagQuery: String,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 80,
    ): List<SearchHit> {
        if (tagQuery.isBlank()) return emptyList()
        val prefs = MapsProviderPrefs(ctx)
        val all = MapsProviders.loadFromBuildConfig()
        val active = all.firstOrNull { it.id == prefs.activePoi && it.endpoint.isNotEmpty() }
            ?: all.firstOrNull { it.kinds.contains("poi") && it.endpoint.isNotEmpty() }
            ?: return emptyList()
        // tagQuery is `key=value`; build a node/way/relation union query.
        val (k, v) = tagQuery.split("=").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        if (k.isBlank() || v.isBlank()) return emptyList()
        val bbox = "$south,$west,$north,$east"
        val ql = """
            [out:json][timeout:25];
            (
              node["$k"="$v"]($bbox);
              way["$k"="$v"]($bbox);
              relation["$k"="$v"]($bbox);
            );
            out center $limit;
        """.trimIndent()
        val body = postForm(active.endpoint, "data=" + URLEncoder.encode(ql, "UTF-8")) ?: return emptyList()
        return parseOverpass(body).take(limit)
    }

    /** Single-shot GET. Returns the response body as a String, or null
     *  on any non-2xx / IOException. */
    private fun fetch(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "CloudSuperApp/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Single-shot POST (form-encoded). Used for Overpass QL. */
    private fun postForm(url: String, formBody: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12000
            readTimeout = 30000
            setRequestProperty("User-Agent", "CloudNav/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { os: OutputStream -> os.write(formBody.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Parse a forward-search response into ranked [SearchHit]s. Each
     *  provider returns its own shape — add a branch when wiring a new one. */
    private fun parseSearch(providerId: String, body: String): List<SearchHit> = runCatching {
        when (providerId) {
            "nominatim_public", "nominatim_self", "locationiq" -> {
                // JSON array of {display_name, lat, lon, type, class, address}.
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                    val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                    val display = o.optStringOrNull("display_name") ?: "Result"
                    val title = display.substringBefore(",").trim().ifEmpty { display }
                    SearchHit(
                        title = title,
                        subtitle = display.substringAfter(",", "").trim().ifEmpty { null },
                        lat = lat, lon = lon,
                        type = o.optStringOrNull("type") ?: o.optStringOrNull("class"),
                    )
                }
            }
            "photon_public", "photon_self" -> {
                // GeoJSON FeatureCollection. geometry.coordinates = [lon,lat].
                val root = JSONObject(body)
                val features = root.optJSONArray("features") ?: return@runCatching emptyList()
                (0 until features.length()).mapNotNull { i ->
                    val f = features.getJSONObject(i)
                    val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates")
                        ?: return@mapNotNull null
                    if (coords.length() < 2) return@mapNotNull null
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val p = f.optJSONObject("properties") ?: JSONObject()
                    val title = p.optStringOrNull("name")
                        ?: p.optStringOrNull("street")
                        ?: p.optStringOrNull("city")
                        ?: "Result"
                    val sub = listOfNotNull(
                        p.optStringOrNull("city"),
                        p.optStringOrNull("state"),
                        p.optStringOrNull("country"),
                    ).joinToString(", ").ifEmpty { null }
                    SearchHit(title, sub, lat, lon, p.optStringOrNull("osm_value"))
                }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** Parse an Overpass `out center` response into POI [SearchHit]s. */
    private fun parseOverpass(body: String): List<SearchHit> = runCatching {
        val root = JSONObject(body)
        val els = root.optJSONArray("elements") ?: return@runCatching emptyList()
        (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val lat: Double
            val lon: Double
            if (e.has("lat") && e.has("lon")) {
                lat = e.getDouble("lat"); lon = e.getDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: return@mapNotNull null
                lat = c.getDouble("lat"); lon = c.getDouble("lon")
            }
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val title = tags.optStringOrNull("name") ?: "(unnamed)"
            val sub = listOfNotNull(
                tags.optStringOrNull("cuisine"),
                tags.optStringOrNull("addr:street"),
                tags.optStringOrNull("opening_hours"),
            ).joinToString(" · ").ifEmpty { null }
            SearchHit(title, sub, lat, lon, tags.optStringOrNull("amenity") ?: tags.optStringOrNull("shop"))
        }
    }.getOrDefault(emptyList())

    /** One forward-search / POI result. [type] is the OSM class/amenity tag
     *  when known (used for icon/colour selection by callers). */
    data class SearchHit(
        val title: String,
        val subtitle: String?,
        val lat: Double,
        val lon: Double,
        val type: String?,
    )

    /** Provider-specific response parsing. Each provider returns its
     *  own JSON shape — add a `when` branch when wiring a new one. */
    private fun parse(providerId: String, body: String): ReverseResult? = runCatching {
        when (providerId) {
            "nominatim_public", "nominatim_self" -> {
                // Nominatim: {display_name, address: {road, neighbourhood,
                //   suburb, city, town, village, country, ...}}
                val root = JSONObject(body)
                val addr = root.optJSONObject("address") ?: return@runCatching null
                ReverseResult(
                    placeName    = root.optStringOrNull("display_name"),
                    neighborhood = addr.optStringOrNull("neighbourhood")
                                    ?: addr.optStringOrNull("suburb"),
                    city         = addr.optStringOrNull("city")
                                    ?: addr.optStringOrNull("town")
                                    ?: addr.optStringOrNull("village"),
                    country      = addr.optStringOrNull("country"),
                )
            }
            "photon_public", "photon_self" -> {
                // Photon: GeoJSON FeatureCollection — first feature is
                //   the best match. properties.{name, city, country,
                //   district, locality, ...}
                val root = JSONObject(body)
                val features = root.optJSONArray("features") ?: return@runCatching null
                if (features.length() == 0) return@runCatching null
                val props = features.getJSONObject(0).optJSONObject("properties")
                    ?: return@runCatching null
                ReverseResult(
                    placeName    = props.optStringOrNull("name"),
                    neighborhood = props.optStringOrNull("district")
                                    ?: props.optStringOrNull("locality"),
                    city         = props.optStringOrNull("city"),
                    country      = props.optStringOrNull("country"),
                )
            }
            "locationiq" -> {
                // LocationIQ mirrors Nominatim's shape.
                val root = JSONObject(body)
                val addr = root.optJSONObject("address") ?: return@runCatching null
                ReverseResult(
                    placeName    = root.optStringOrNull("display_name"),
                    neighborhood = addr.optStringOrNull("neighbourhood")
                                    ?: addr.optStringOrNull("suburb"),
                    city         = addr.optStringOrNull("city")
                                    ?: addr.optStringOrNull("town"),
                    country      = addr.optStringOrNull("country"),
                )
            }
            else -> null  // Other providers wired in Push 3.5.
        }
    }.getOrNull()

    data class ReverseResult(
        val placeName: String?,
        val neighborhood: String?,
        val city: String?,
        val country: String?,
    )
}

/** JSONObject.optString returns "" instead of null for missing keys —
 *  trip-line for our enricher which writes "" into the DB and then
 *  later thinks the slot is filled. This helper returns true-null. */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.takeIf { it.isNotEmpty() }
}
