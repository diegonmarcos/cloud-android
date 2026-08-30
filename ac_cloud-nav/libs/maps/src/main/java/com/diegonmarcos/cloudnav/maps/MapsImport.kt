package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports the user's own location history from a JSON file (Configs → Tracker →
 * "Import JSON"). Parses the `travel-data.json` shape
 * (front/b-MyData/mymaps-mytrips) — an array of `trips`, each a stay in a city
 * from `dateIn` to `dateOut` at `lat`/`lng` — and inserts each as an enriched
 * Stop, so it flows into Daily / Stops / Explored / MyTrips exactly like tracked
 * data (same MapsDb.insertStop + enrichStop path the demo seed uses).
 *
 * Key handling is deliberately lenient (data comes from an external file):
 *   • lat  ← "lat"
 *   • lon  ← "lng" | "lon" | "longitude"
 *   • start← "dateIn" | "startedAt" | "start"   (ISO yyyy-MM-dd OR epoch ms)
 *   • end  ← "dateOut" | "endedAt" | "end"      (defaults to start if absent)
 *   • place← "place" | "nomadRegion" | "city"
 *   • hood ← "neighborhood" | "state"
 *   • city ← "city"
 *   • ctry ← "country"
 * The trips array may be at the JSON root (a bare array) or under a "trips" key.
 */
object MapsImport {

    data class StopInsert(
        val startedAt: Long, val endedAt: Long,
        val lat: Double, val lon: Double,
        val place: String?, val neighborhood: String?, val city: String?, val country: String?,
    )

    /** Pure JSON → stops parse (no DB / no Android IO) — exposed for testing.
     *  Skips rows with no usable coordinate or an unparseable start date. */
    fun parseStops(json: String): List<StopInsert> = runCatching {
        val root = org.json.JSONTokener(json).nextValue()
        val trips: JSONArray = when {
            root is JSONArray -> root
            root is JSONObject && root.optJSONArray("trips") != null -> root.getJSONArray("trips")
            else -> return emptyList()
        }
        (0 until trips.length()).mapNotNull { i ->
            val o = trips.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDoubleOrNull("lat") ?: return@mapNotNull null
            val lon = o.optDoubleOrNull("lng") ?: o.optDoubleOrNull("lon")
                ?: o.optDoubleOrNull("longitude") ?: return@mapNotNull null
            val start = firstEpoch(o, "dateIn", "startedAt", "start") ?: return@mapNotNull null
            val end = firstEpoch(o, "dateOut", "endedAt", "end") ?: start
            StopInsert(
                startedAt = start,
                endedAt = maxOf(end, start),
                lat = lat, lon = lon,
                place = o.firstNonBlank("place", "nomadRegion", "city"),
                neighborhood = o.firstNonBlank("neighborhood", "state"),
                city = o.firstNonBlank("city"),
                country = o.firstNonBlank("country"),
            )
        }
    }.getOrDefault(emptyList())

    data class Result(val inserted: Int, val error: String?)

    /** Read [uri]'s JSON, parse, and insert all stops in one transaction.
     *  Call off the main thread. */
    fun importFrom(ctx: Context, uri: Uri): Result {
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return Result(0, "Could not read the file")
        val stops = parseStops(text)
        if (stops.isEmpty()) return Result(0, "No importable trips found in that file")
        val db = MapsDb.get(ctx)
        db.runInTransaction {
            for (s in stops) {
                val id = db.insertStop(MapsDb.StopRow(startedAt = s.startedAt, endedAt = s.endedAt, lat = s.lat, lon = s.lon))
                db.enrichStop(id, s.place, s.neighborhood, s.city, s.country)
            }
        }
        return Result(stops.size, null)
    }

    // ── helpers ──────────────────────────────────────────────────────────
    /** First of [keys] that yields an epoch-ms: an ISO "yyyy-MM-dd" (UTC
     *  midnight) or a raw epoch-ms number. Null if none parse. */
    private fun firstEpoch(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            // Numeric → treat as epoch ms directly.
            val num = o.optLongOrNull(k)
            if (num != null && num > 0L) return num
            val s = o.optString(k, "").trim()
            if (s.isEmpty()) continue
            val iso = runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(s)?.time
            }.getOrNull()
            if (iso != null) return iso
        }
        return null
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val d = optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        val v = opt(key)
        return when (v) {
            is Number -> v.toLong()
            else -> null
        }
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
