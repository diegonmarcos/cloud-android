package com.diegonmarcos.cloudnav.maps

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route geometry + per-mode distance/time. Data-driven travel modes
 * (build.json::ui.travel_modes, baked UI_TRAVEL_MODES_B64):
 *   • engine "valhalla" → real road route via the FOSSGIS Valhalla endpoint
 *     (UI_ROUTING_ENDPOINT), `costing` = auto/pedestrian/bicycle/bus. Returns
 *     true distance + time + the decoded polyline to draw.
 *   • engine "geodesic" → great-circle straight line ÷ avg_speed_kmh (boat /
 *     flight / swim — no router does these). `estimated` flags approximations.
 *
 * All calls MUST run off the main thread.
 */
object MapsRouting {

    data class Mode(
        val id: String, val label: String, val emoji: String,
        val engine: String, val costing: String, val avgSpeedKmh: Double, val estimated: Boolean,
    )

    /** distanceKm + durationSec + the route line as [lat,lon] points. */
    data class RouteResult(
        val distanceKm: Double,
        val durationSec: Double,
        val geometry: List<DoubleArray>,
        val estimated: Boolean,
    )

    fun modes(): List<Mode> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_TRAVEL_MODES_B64, Base64.DEFAULT)))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Mode(
                id = o.optString("id"),
                label = o.optString("label", o.optString("id")),
                emoji = o.optString("emoji", ""),
                engine = o.optString("engine", "valhalla"),
                costing = o.optString("costing", "auto"),
                avgSpeedKmh = o.optDouble("avg_speed_kmh", 50.0),
                estimated = o.optBoolean("estimated", false),
            )
        }
    }.getOrDefault(emptyList())

    fun defaultModeId(): String = BuildConfig.UI_DEFAULT_TRAVEL_MODE.ifBlank { modes().firstOrNull()?.id ?: "car" }

    /** Compute a route through [stops] ([lat,lon] each, ≥2) for [mode]. Returns
     *  null on failure / too few stops. */
    fun route(mode: Mode, stops: List<DoubleArray>): RouteResult? {
        if (stops.size < 2) return null
        return if (mode.engine == "geodesic") geodesic(mode, stops) else valhalla(mode, stops)
    }

    private fun geodesic(mode: Mode, stops: List<DoubleArray>): RouteResult {
        var km = 0.0
        for (i in 0 until stops.size - 1) {
            km += haversineKm(stops[i][0], stops[i][1], stops[i + 1][0], stops[i + 1][1])
        }
        val speed = if (mode.avgSpeedKmh > 0) mode.avgSpeedKmh else 1.0
        return RouteResult(km, km / speed * 3600.0, stops.map { doubleArrayOf(it[0], it[1]) }, true)
    }

    private fun valhalla(mode: Mode, stops: List<DoubleArray>): RouteResult? = runCatching {
        val locs = JSONArray()
        stops.forEach { locs.put(JSONObject().put("lat", it[0]).put("lon", it[1])) }
        val body = JSONObject()
            .put("locations", locs)
            .put("costing", mode.costing)
            .put("units", "kilometers")
            .toString()
        val url = BuildConfig.UI_ROUTING_ENDPOINT + "?json=" + URLEncoder.encode(body, "UTF-8")
        val resp = httpGet(url) ?: return@runCatching null
        val trip = JSONObject(resp).optJSONObject("trip") ?: return@runCatching null
        val summary = trip.optJSONObject("summary") ?: return@runCatching null
        val geom = mutableListOf<DoubleArray>()
        val legs = trip.optJSONArray("legs")
        if (legs != null) {
            for (i in 0 until legs.length()) {
                val shape = legs.getJSONObject(i).optString("shape")
                if (shape.isNotEmpty()) geom += decodePolyline6(shape)
            }
        }
        RouteResult(
            distanceKm = summary.optDouble("length", 0.0),
            durationSec = summary.optDouble("time", 0.0),
            geometry = if (geom.isNotEmpty()) geom else stops.map { doubleArrayOf(it[0], it[1]) },
            estimated = mode.estimated,
        )
    }.getOrNull()

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 30000
            setRequestProperty("User-Agent", "CloudNav/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Decode a Google/Valhalla encoded polyline at precision 6 → [lat,lon]. */
    fun decodePolyline6(encoded: String): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        var index = 0; var lat = 0; var lon = 0
        val factor = 1e6
        while (index < encoded.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(doubleArrayOf(lat / factor, lon / factor))
        }
        return out
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── formatters (shared by the Routes UI) ─────────────────────────
    fun fmtDistance(km: Double): String =
        if (km < 1.0) "${(km * 1000).roundToInt()} m" else "%.1f km".format(km)

    fun fmtDuration(sec: Double): String {
        val total = (sec / 60).roundToInt()      // minutes
        val h = total / 60; val m = total % 60
        return if (h > 0) "${h} h ${m} min" else "${m} min"
    }
}
