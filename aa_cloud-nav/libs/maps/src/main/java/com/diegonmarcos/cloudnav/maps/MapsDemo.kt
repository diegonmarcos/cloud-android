package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Tester seed data — a 3-day trip data-driven from build.json::ui.demo_stops
 * (baked into BuildConfig.UI_DEMO_STOPS_B64). Lets Timeline / Daily / Stops /
 * day-map render real content without a live tracker. Inserted by [seed].
 *
 * Each demo day is expressed as `days_ago` (0 = today), resolved against
 * *today's* UTC midnight at seed time — NOT a fixed historical date. Every
 * screen that reads this data (MapsDailyFragment, MapsStopsFragment,
 * MytripsDashboardFragment, …) queries a window ending at
 * `System.currentTimeMillis()`; a fixed past date drifts out of that window
 * and the "loaded" demo silently never appears anywhere (the bug this fixes).
 * Relative-to-today means it's always inside every such window.
 *
 * Idempotent per calendar day (Configs → Tracker → "Load demo data"):
 * re-tapping the same day is a no-op; tapping again on a later day reseeds a
 * fresh window ending then.
 */
object MapsDemo {

    data class DemoStop(
        val startMin: Int, val endMin: Int,
        val lat: Double, val lon: Double,
        val place: String, val neighborhood: String, val city: String, val country: String,
    )

    data class DemoDay(val daysAgo: Int, val stops: List<DemoStop>)

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
            DemoDay(daysAgo = o.optInt("days_ago"), stops = stops)
        }
    }

    /** UTC midnight of [instantMs] — the anchor every [demoDays] entry's
     *  `daysAgo` is measured back from. Pure function of the clock, so tests
     *  can pass a fixed instant instead of depending on the real current time. */
    fun utcMidnightOf(instantMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = instantMs
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayKey(instantMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(instantMs))

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("maps_demo_prefs", Context.MODE_PRIVATE)

    /** True once [seed] has already run for today (UTC) — the idempotency guard. */
    fun isSeededToday(ctx: Context): Boolean =
        prefs(ctx).getString(KEY_SEEDED_DAY, null) == dayKey(utcMidnightOf(System.currentTimeMillis()))

    /** Insert every demo day, anchored to today. Returns the number of stops
     *  inserted; 0 if already seeded today or no data configured. */
    fun seed(ctx: Context): Int {
        if (demoDays.isEmpty() || isSeededToday(ctx)) return 0
        val db = MapsDb.get(ctx)
        val today = utcMidnightOf(System.currentTimeMillis())
        var inserted = 0
        for (day in demoDays) {
            val dayStart = today - day.daysAgo * DAY_MS
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
        prefs(ctx).edit().putString(KEY_SEEDED_DAY, dayKey(today)).apply()
        return inserted
    }

    private const val DAY_MS = 24L * 3600_000L
    private const val KEY_SEEDED_DAY = "seeded_day"
}
