package com.diegonmarcos.superapp.cal

import android.content.Context
import org.json.JSONArray

/**
 * SharedPreferences-backed local cache of synced calendar events,
 * plus the sync bookkeeping (last-sync timestamp, HTTP ETag) that
 * [CalSync] needs to avoid re-fetching unchanged feeds. Follows the
 * same house style as core.NotificationStore: one prefs file, JSON
 * strings as values, no Room/DB dependency.
 */
object CalendarStore {
    private const val PREFS = "calendar_cache"
    private const val EVENTS_PREFIX = "events_"
    private const val LAST_SYNC_PREFIX = "last_sync_"
    private const val ETAG_PREFIX = "etag_"

    // A single public holiday/moon-phase feed tops out at a few
    // hundred VEVENTs, but nothing stops a misbehaving or
    // multi-year feed from returning thousands. Prefs values are
    // held in memory and written as one blob, so an unbounded feed
    // could bloat memory and disk I/O on every read/write. Cap per
    // calendar and drop the oldest (by start time) once over.
    private const val MAX_EVENTS_PER_CALENDAR = 2000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Replace the full cached event set for one calendar. Called
     *  after a successful fetch+parse — the cache is always a
     *  wholesale swap per calendar, never a merge, since public ICS
     *  feeds don't expose incremental diffs beyond ETag/If-None-Match
     *  (which only tells us "unchanged", not "what changed"). */
    fun replaceEvents(ctx: Context, calendarId: String, events: List<CalEvent>) {
        val capped = if (events.size > MAX_EVENTS_PER_CALENDAR) {
            events.sortedByDescending { it.startUtcMillis }.take(MAX_EVENTS_PER_CALENDAR)
        } else {
            events
        }
        val arr = JSONArray()
        for (e in capped) arr.put(e.toJson())
        prefs(ctx).edit().putString(EVENTS_PREFIX + calendarId, arr.toString()).apply()
    }

    private fun eventsForCalendar(ctx: Context, calendarId: String): List<CalEvent> {
        val raw = prefs(ctx).getString(EVENTS_PREFIX + calendarId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CalEvent.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Union of cached events across [calendarIds] whose window
     *  overlaps [fromUtc, toUtc). Used by the UI layer to render a
     *  merged view without knowing about per-calendar storage. */
    fun eventsFor(ctx: Context, calendarIds: List<String>, fromUtc: Long, toUtc: Long): List<CalEvent> {
        val out = mutableListOf<CalEvent>()
        for (id in calendarIds) {
            for (e in eventsForCalendar(ctx, id)) {
                if (e.endUtcMillis >= fromUtc && e.startUtcMillis < toUtc) out.add(e)
            }
        }
        return out.sortedBy { it.startUtcMillis }
    }

    fun lastSync(ctx: Context, calendarId: String): Long =
        prefs(ctx).getLong(LAST_SYNC_PREFIX + calendarId, 0L)

    fun setLastSync(ctx: Context, calendarId: String, millis: Long) {
        prefs(ctx).edit().putLong(LAST_SYNC_PREFIX + calendarId, millis).apply()
    }

    fun etag(ctx: Context, calendarId: String): String? =
        prefs(ctx).getString(ETAG_PREFIX + calendarId, null)

    fun setEtag(ctx: Context, calendarId: String, value: String?) {
        val e = prefs(ctx).edit()
        if (value == null) e.remove(ETAG_PREFIX + calendarId) else e.putString(ETAG_PREFIX + calendarId, value)
        e.apply()
    }
}
