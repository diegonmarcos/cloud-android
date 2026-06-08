package com.diegonmarcos.superapp

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
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
