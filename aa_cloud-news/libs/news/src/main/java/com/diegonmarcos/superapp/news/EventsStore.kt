package com.diegonmarcos.superapp.news

import android.content.Context
import org.json.JSONArray

/**
 * SharedPreferences-backed local cache of synced calendar events, plus
 * the sync bookkeeping (last-sync timestamp, HTTP ETag) [EventsSync]
 * needs to avoid re-fetching unchanged feeds. Mirrors
 * ea_cloud-calendar's `CalendarStore` (CalendarStore.kt) field-for-field
 * — same house style as [NewsStore]: one prefs file, JSON strings as
 * values, no Room/DB dependency, replace-only-on-success (see
 * [EventsSync]).
 */
object EventsStore {
    private const val PREFS = "events_cache"
    private const val EVENTS_PREFIX = "events_"
    private const val LAST_SYNC_PREFIX = "last_sync_"
    private const val ETAG_PREFIX = "etag_"

    // Same reasoning/cap as CalendarStore.MAX_EVENTS_PER_CALENDAR: a
    // single public holiday/moon-phase/sports feed tops out at a few
    // hundred VEVENTs, but nothing stops a misbehaving or multi-year
    // feed from returning thousands, and prefs values are held in
    // memory and written as one blob.
    private const val MAX_EVENTS_PER_CALENDAR = 2000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Replace the full cached event set for one calendar — called
     *  after a successful fetch+parse only (see [EventsSync]), never a
     *  merge, same as [NewsStore]'s replace* functions: a failed
     *  refresh must leave the previous cache untouched. */
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
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { CalEvent.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    /** Union of cached events across [calendarIds] whose window
     *  overlaps `[fromUtc, toUtc)`, sorted by start time — same
     *  semantics as `CalendarStore.eventsFor`. Used by
     *  [com.diegonmarcos.cloudnews.NewsBridge].events so the UI never
     *  needs to know about per-calendar storage. */
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

/**
 * Local bookmarks — events the user explicitly saved, as a full
 * [SavedEvent] snapshot (title/start/end/allDay/location/calendar
 * id+label+colour) rather than just an id, so a saved event still
 * renders correctly even after it ages out of [EventsStore]'s feed
 * cache or its calendar is removed from data/events.json in a future
 * rebuild — see [SavedEvent]'s kdoc. Same house style/shape as
 * [SavedStore] (news articles): one prefs file, JSON array of the
 * model's own toJson()/fromJson, toggle-by-id.
 */
object SavedEventsStore {
    private const val PREFS = "events_saved"
    private const val KEY = "saved_events"

    // Same defensive-bound reasoning as SavedStore.MAX_SAVED: user-
    // driven, one tap at a time, but still capped so an accidental
    // save-everything loop can't grow this prefs value unbounded.
    private const val MAX_SAVED = 2000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Most-recently-saved first. */
    fun saved(ctx: Context): List<SavedEvent> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { SavedEvent.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    fun isSaved(ctx: Context, id: String): Boolean =
        id.isNotBlank() && saved(ctx).any { it.id == id }

    /** Adds [event] if not already saved (by id), else removes it.
     *  Returns the new saved state: true if it is now saved, false if
     *  the toggle just removed it — same contract as [SavedStore.toggle]. */
    fun toggle(ctx: Context, event: SavedEvent): Boolean {
        val current = saved(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == event.id }
        val nowSaved: Boolean
        if (idx >= 0) {
            current.removeAt(idx)
            nowSaved = false
        } else {
            current.add(0, event)
            nowSaved = true
        }
        val capped = if (current.size > MAX_SAVED) current.take(MAX_SAVED) else current
        val arr = JSONArray()
        for (e in capped) arr.put(e.toJson())
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        return nowSaved
    }
}
