package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Tester seed data — a rich 5-year historical trip (1987-1992), data-driven
 * from build.json::ui.demo_stops (baked into BuildConfig.UI_DEMO_STOPS_B64).
 * Lets Timeline / Daily / Stops / Explored / MyTrips render real content
 * without a live tracker. Inserted by [seed].
 *
 * Dates are FIXED (each demo day carries an explicit `date`, e.g.
 * "1987-07-18") rather than relative to "today" — deliberately historical so
 * the demo trip can never collide with the user's real tracked data (nobody
 * has real GPS history from 1987-1992). This only works because every screen
 * that reads Stops (MapsDailyFragment, MapsStopsFragment, MapsExploredFragment,
 * MytripsDashboardFragment/StatsFragment) queries ALL-TIME
 * (`stopsBetween(0L, now)`), not a rolling "last N years" window — a bounded
 * recent-only query would hide 30+-year-old data just as badly as it
 * previously hid data anchored the other way (a fixed date landing outside a
 * "last 5 years" window). Widening those queries to all-time is what makes a
 * fixed historical demo date safe to use.
 *
 * Seeded once (idempotent) — the historical range never changes, so there's
 * no reason to reseed on a later day the way a relative-to-today scheme would.
 */
object MapsDemo {

    data class DemoStop(
        val startMin: Int, val endMin: Int,
        val lat: Double, val lon: Double,
        val place: String, val neighborhood: String, val city: String, val country: String,
    )

    data class DemoDay(val dateIso: String, val stops: List<DemoStop>)

    private val root: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.UI_DEMO_STOPS_B64, Base64.DEFAULT))) }
            .getOrDefault(JSONObject())
    }

    val demoDays: List<DemoDay> by lazy {
        val arr = root.optJSONArray("days") ?: return@lazy emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val stopsArr = o.optJSONArray("stops")
            val stops = if (stopsArr == null) emptyList() else (0 until stopsArr.length()).map { j ->
                val s = stopsArr.getJSONObject(j)
                DemoStop(
                    startMin = s.optInt("start_min"),
                    endMin = s.optInt("end_min"),
                    lat = s.optDouble("lat"),
                    lon = s.optDouble("lon"),
                    place = s.optString("place"),
                    neighborhood = s.optString("neighborhood"),
                    city = s.optString("city"),
                    country = s.optString("country"),
                )
            }
            DemoDay(dateIso = o.optString("date"), stops = stops)
        }
    }

    /** UTC midnight epoch of an ISO "yyyy-MM-dd" date string. Pure — no clock
     *  dependency, since these are fixed historical dates, not "today". */
    fun utcMidnightOf(dateIso: String): Long =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateIso)?.time
        }.getOrNull() ?: 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("maps_demo_prefs", Context.MODE_PRIVATE)

    /** True once [seed] has already inserted this fixed historical dataset. */
    fun isSeeded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SEEDED, false)

    /** Insert every demo day at its fixed historical date. Returns the number
     *  of stops inserted; 0 if already seeded or no data configured. */
    fun seed(ctx: Context): Int {
        if (demoDays.isEmpty() || isSeeded(ctx)) return 0
        val db = MapsDb.get(ctx)
        var inserted = 0
        for (day in demoDays) {
            val dayStart = utcMidnightOf(day.dateIso)
            if (dayStart <= 0L) continue
            for (s in day.stops) {
                val id = db.insertStop(
                    MapsDb.StopRow(
                        startedAt = dayStart + s.startMin * 60_000L,
                        endedAt = dayStart + s.endMin * 60_000L,
                        lat = s.lat, lon = s.lon,
                    )
                )
                db.enrichStop(id, s.place, s.neighborhood, s.city, s.country)
                inserted++
            }
        }
        prefs(ctx).edit().putBoolean(KEY_SEEDED, true).apply()
        return inserted
    }

    private const val KEY_SEEDED = "seeded"
}
