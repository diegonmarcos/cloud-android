package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Tester seed data — FULL daily coverage across a 5-year historical span
 * (1987-1992), data-driven from build.json::ui.demo_stops (baked into
 * BuildConfig.UI_DEMO_STOPS_B64). Lets Timeline / Daily / Stops / Explored /
 * MyTrips render real content — one populated day for EVERY calendar day in
 * the range, not a sparse sample — without a live tracker.
 *
 * Generated, not hand-enumerated: [seed] cycles through [cityTemplates] (each
 * a full day's worth of stops), staying [stayDays] days per city before
 * rotating to the next, for every day between [rangeStartIso] and
 * [rangeEndIso]. ~2192 days × ~3 stops ≈ several thousand rows — inserted
 * inside a single [MapsDb.runInTransaction] so it stays fast.
 *
 * Dates are FIXED (never relative to "today") — deliberately historical so
 * the demo trip can never collide with the user's real tracked data (nobody
 * has real GPS history from 1987-1992). This only works because every screen
 * that reads Stops (MapsDailyFragment, MapsStopsFragment, MapsExploredFragment,
 * MytripsDashboardFragment/StatsFragment) queries ALL-TIME
 * (`stopsBetween(0L, now)`), not a rolling "last N years" window.
 *
 * Seeded once (idempotent) — the historical range never changes.
 */
object MapsDemo {

    data class DemoStop(
        val startMin: Int, val endMin: Int,
        val lat: Double, val lon: Double,
        val place: String, val neighborhood: String,
    )

    data class CityTemplate(val city: String, val country: String, val stops: List<DemoStop>)

    private val root: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.UI_DEMO_STOPS_B64, Base64.DEFAULT))) }
            .getOrDefault(JSONObject())
    }

    val rangeStartIso: String get() = root.optString("range_start")
    val rangeEndIso: String get() = root.optString("range_end")
    val stayDays: Int get() = root.optInt("stay_days", 1).coerceAtLeast(1)

    /** Shared place variations — the same 3 distinct places (offset + time
     *  window) applied to EVERY city, so the data stays DRY and small (fits
     *  the 64KB BuildConfig-string limit) while still giving each city 3
     *  distinct places. */
    private data class PlaceVariation(
        val suffix: String, val dLat: Double, val dLon: Double, val startMin: Int, val endMin: Int,
    )

    private val placeVariations: List<PlaceVariation> by lazy {
        val arr = root.optJSONArray("place_variations")
            ?: return@lazy listOf(PlaceVariation("Stay", 0.0, 0.0, 0, 600))
        (0 until arr.length()).map { j ->
            val v = arr.getJSONObject(j)
            PlaceVariation(
                suffix = v.optString("suffix", "Stay"),
                dLat = v.optDouble("dlat", 0.0), dLon = v.optDouble("dlon", 0.0),
                startMin = v.optInt("start_min"), endMin = v.optInt("end_min", 600),
            )
        }
    }

    val cityTemplates: List<CityTemplate> by lazy {
        val arr = root.optJSONArray("city_templates") ?: return@lazy emptyList()
        val variations = placeVariations
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val city = o.optString("city"); val country = o.optString("country")
            val baseLat = o.optDouble("lat"); val baseLon = o.optDouble("lon")
            // Expand the shared variations into this city's distinct places.
            val stops = variations.map { v ->
                DemoStop(
                    startMin = v.startMin, endMin = v.endMin,
                    lat = baseLat + v.dLat, lon = baseLon + v.dLon,
                    place = "$city — ${v.suffix}", neighborhood = v.suffix,
                )
            }
            CityTemplate(city = city, country = country, stops = stops)
        }
    }

    /** UTC midnight epoch of an ISO "yyyy-MM-dd" date string. Pure — no clock
     *  dependency, since these are fixed historical dates, not "today". */
    fun utcMidnightOf(dateIso: String): Long =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateIso)?.time
        }.getOrNull() ?: 0L

    /** Inclusive day-count of [rangeStartIso]..[rangeEndIso] — pure, no DB. */
    val totalDays: Int
        get() {
            val start = utcMidnightOf(rangeStartIso); val end = utcMidnightOf(rangeEndIso)
            if (start <= 0L || end < start) return 0
            return ((end - start) / DAY_MS).toInt() + 1
        }

    /** Which [cityTemplates] entry covers day index [dayIndex] (0 = range start),
     *  cycling every [stayDays] days. Pure — the whole generation algorithm in
     *  one testable function. */
    fun cityForDayIndex(dayIndex: Int): CityTemplate {
        val cities = cityTemplates
        return cities[(dayIndex / stayDays) % cities.size]
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("maps_demo_prefs", Context.MODE_PRIVATE)

    /** A signature of the ACTUAL dataset content (range + stay + every city's
     *  name/country/stop-count), not a version number someone has to remember
     *  to bump. Editing build.json::ui.demo_stops in any way that changes what
     *  gets generated automatically produces a different signature, so a
     *  device that already seeded an OLDER shape of this dataset reseeds
     *  instead of silently no-op'ing forever on a stale boolean flag — the
     *  exact bug this replaces (a bare "seeded=true" surviving across this
     *  dataset's several redesigns this project went through). */
    private fun contentSignature(): String {
        val cities = cityTemplates.joinToString("|") { "${it.city}:${it.country}:${it.stops.size}" }
        // Include the place-variation GEOMETRY (offsets + time windows), not just
        // the stop COUNT — otherwise re-tuning the offsets (e.g. spreading places
        // so PLACES mode reads as denser than CITY) leaves already-seeded devices
        // stuck on the old coordinates, since name/country/count are unchanged.
        val variations = placeVariations.joinToString(",") { "${it.suffix}@${it.dLat}/${it.dLon}:${it.startMin}-${it.endMin}" }
        return "$rangeStartIso..$rangeEndIso@$stayDays#$cities&$variations"
    }

    /** True once [seed] has already inserted THIS EXACT dataset shape. */
    fun isSeeded(ctx: Context): Boolean = prefs(ctx).getString(KEY_SEEDED_SIGNATURE, null) == contentSignature()

    /** Generate + insert one populated day for every day in the historical
     *  range. Returns the number of stops inserted; 0 if already seeded or no
     *  data configured. Call off the main thread — this can be several
     *  thousand rows (still fast: one transaction, not one fsync per row). */
    fun seed(ctx: Context): Int {
        val days = totalDays
        if (days <= 0 || cityTemplates.isEmpty() || isSeeded(ctx)) return 0
        val db = MapsDb.get(ctx)
        val rangeStart = utcMidnightOf(rangeStartIso)
        var inserted = 0
        db.runInTransaction {
            for (dayIndex in 0 until days) {
                val dayStart = rangeStart + dayIndex * DAY_MS
                val city = cityForDayIndex(dayIndex)
                for (s in city.stops) {
                    val id = db.insertStop(
                        MapsDb.StopRow(
                            startedAt = dayStart + s.startMin * 60_000L,
                            endedAt = dayStart + s.endMin * 60_000L,
                            lat = s.lat, lon = s.lon,
                        )
                    )
                    db.enrichStop(id, s.place, s.neighborhood, city.city, city.country)
                    inserted++
                }
            }
        }
        prefs(ctx).edit().putString(KEY_SEEDED_SIGNATURE, contentSignature()).apply()
        return inserted
    }

    /** Clears the seed signature — a fresh "Load demo data" tap reseeds even
     *  if this exact dataset was already inserted. Paired with wiping the
     *  Stops table itself (see Configs → Tracker → "Reset all tracker data",
     *  which calls both — resetting the DB alone left this flag stale and
     *  Load-demo-data a permanent no-op after a reset). */
    fun resetSeedFlag(ctx: Context) = prefs(ctx).edit().remove(KEY_SEEDED_SIGNATURE).apply()

    private const val DAY_MS = 24L * 3600_000L
    private const val KEY_SEEDED_SIGNATURE = "seeded_signature"
}
