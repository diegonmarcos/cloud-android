package com.diegonmarcos.superapp

import android.content.Context
import android.util.Log

/**
 * Walks unenriched Stop rows and fires one reverse-geocode call per
 * row, throttled to 1 req/sec to respect Nominatim's public fair-use
 * ceiling. Idempotent: re-runs are cheap because [MapsDb.enrichStop]
 * stamps `enriched_at` even on a null result, so failed lookups
 * aren't retried on the same DB pass.
 *
 * Dispatch:
 *   • Called from [LocationTrackerService] on each new Stop emission.
 *   • Runs on a background Thread {} (no coroutines / WorkManager
 *     dependency to manage) — fire-and-forget. Re-entry-safe because
 *     SQLite handles concurrent reads internally and writes are
 *     queued.
 *   • [enrichOldest] flushes any backlog (used by Timeline pull-to-
 *     refresh + Push 3.5 export pre-flight).
 *
 * NOT wired for foreground polling — only fires when there's actual
 * work. Net cost ≈ ~10 HTTP calls / day for a typical traveller; well
 * under any provider's free tier.
 */
object StopsEnricher {

    private const val TAG = "StopsEnricher"
    /** Nominatim fair-use says max 1 req/sec; pad to 1.1s to be safe. */
    private const val MIN_GAP_MS = 1_100L
    @Volatile private var running = false

    /** Async kick — returns immediately. If a worker is already
     *  walking the queue this is a no-op (Stops collected mid-walk
     *  get picked up by the existing pass since [MapsDb.unenrichedStops]
     *  re-queries every iteration). */
    fun kickAsync(ctx: Context) {
        if (running) return
        Thread {
            running = true
            try { walk(ctx.applicationContext) }
            finally { running = false }
        }.apply {
            name = "StopsEnricher"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Synchronous walk — caller is on a background thread already
     *  (e.g. Export's pre-flight). Honours the [MIN_GAP_MS] throttle. */
    fun enrichOldest(ctx: Context): Int {
        return walk(ctx)
    }

    private fun walk(ctx: Context): Int {
        val db = MapsDb.get(ctx)
        var processed = 0
        var lastCallMs = 0L
        while (true) {
            val batch = db.unenrichedStops(limit = 20)
            if (batch.isEmpty()) break
            for (stop in batch) {
                val now = System.currentTimeMillis()
                val gap = now - lastCallMs
                if (gap < MIN_GAP_MS) {
                    runCatching { Thread.sleep(MIN_GAP_MS - gap) }
                }
                lastCallMs = System.currentTimeMillis()
                runCatching {
                    enrichOne(ctx, stop)
                    processed++
                }.onFailure {
                    Log.w(TAG, "enrichOne failed for stop=${stop.id}: ${it.message}")
                    // Still stamp `enriched_at` so we don't loop on
                    // a permanently-failing point.
                    db.enrichStop(stop.id, null, null, null, null)
                }
            }
        }
        return processed
    }

    /** Lookup one Stop — checks the rounded-coord cache first
     *  (10m grid via 4 decimals) so repeat visits to the same block
     *  don't double-call the geocoder. */
    private fun enrichOne(ctx: Context, stop: MapsDb.EnrichableStop) {
        val db = MapsDb.get(ctx)
        val key = "%.4f,%.4f".format(stop.lat, stop.lon)
        val cached = db.cachedReverse(key)
        val result: MapsProviderClient.ReverseResult? = if (cached != null) {
            // Cache hit — deserialise. Cached blob is the raw provider
            // JSON; re-parse via the same path.
            parseCached(cached)
        } else {
            // Cache miss — call provider, store the parsed result back.
            val fresh = MapsProviderClient.reverseGeocode(ctx, stop.lat, stop.lon)
            if (fresh != null) {
                val blob = org.json.JSONObject().apply {
                    put("place_name",   fresh.placeName ?: org.json.JSONObject.NULL)
                    put("neighborhood", fresh.neighborhood ?: org.json.JSONObject.NULL)
                    put("city",         fresh.city ?: org.json.JSONObject.NULL)
                    put("country",      fresh.country ?: org.json.JSONObject.NULL)
                }
                db.cacheReverse(key, blob.toString())
            }
            fresh
        }
        db.enrichStop(
            id           = stop.id,
            placeName    = result?.placeName,
            neighborhood = result?.neighborhood,
            city         = result?.city,
            country      = result?.country,
        )
    }

    /** Restore a [MapsProviderClient.ReverseResult] from the cached
     *  blob we wrote on first lookup. */
    private fun parseCached(blob: String): MapsProviderClient.ReverseResult? = runCatching {
        val o = org.json.JSONObject(blob)
        MapsProviderClient.ReverseResult(
            placeName    = o.optString("place_name").takeIf { it.isNotEmpty() && it != "null" },
            neighborhood = o.optString("neighborhood").takeIf { it.isNotEmpty() && it != "null" },
            city         = o.optString("city").takeIf { it.isNotEmpty() && it != "null" },
            country      = o.optString("country").takeIf { it.isNotEmpty() && it != "null" },
        )
    }.getOrNull()
}
