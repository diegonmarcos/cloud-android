package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Tester seed data — a full day on 1987-07-18 (Rio de Janeiro), data-driven
 * from build.json::ui.demo_stops (baked into BuildConfig.UI_DEMO_STOPS_B64).
 * Lets Timeline / Daily / Stops / day-map render real content without a live
 * tracker. Inserted by [seed] (idempotent — skips if that day already has
 * stops). Wired to Configs → Tracker → "Load demo data".
 */
object MapsDemo {

    data class DemoStop(
        val startMin: Int, val endMin: Int,
        val lat: Double, val lon: Double,
        val place: String, val neighborhood: String, val city: String, val country: String,
    )

    private val root: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.UI_DEMO_STOPS_B64, Base64.DEFAULT))) }
            .getOrDefault(JSONObject())
    }

    /** Local midnight (epoch ms) of the demo day = 1987-07-18 00:00 UTC. */
    val dayEpochMs: Long get() = root.optLong("day_epoch_ms", 0L)

    fun stops(): List<DemoStop> {
        val arr = root.optJSONArray("stops") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DemoStop(
                startMin = o.optInt("start_min"),
                endMin = o.optInt("end_min"),
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
                place = o.optString("place"),
                neighborhood = o.optString("neighborhood"),
                city = o.optString("city"),
                country = o.optString("country"),
            )
        }
    }

    /** True when the demo day already has stops in the DB. */
    fun isSeeded(ctx: Context): Boolean {
        if (dayEpochMs <= 0L) return false
        return MapsDb.get(ctx).stopsBetween(dayEpochMs, dayEpochMs + DAY_MS).isNotEmpty()
    }

    /** Insert the demo day (already-enriched stops). Returns the number
     *  inserted; 0 if already seeded or no data. Idempotent. */
    fun seed(ctx: Context): Int {
        if (dayEpochMs <= 0L || isSeeded(ctx)) return 0
        val db = MapsDb.get(ctx)
        val demo = stops()
        for (s in demo) {
            val id = db.insertStop(
                MapsDb.StopRow(
                    startedAt = dayEpochMs + s.startMin * 60_000L,
                    endedAt = dayEpochMs + s.endMin * 60_000L,
                    lat = s.lat, lon = s.lon,
                )
            )
            db.enrichStop(id, s.place, s.neighborhood, s.city, s.country)
        }
        return demo.size
    }

    private const val DAY_MS = 24L * 3600_000L
}
