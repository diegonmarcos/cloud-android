package com.diegonmarcos.superapp.news

import org.json.JSONArray
import org.json.JSONObject

/**
 * EVENTS (ICS calendar feeds) — the data half of the Events tab.
 * [CalEvent] is copied field-for-field from
 * ea_cloud-calendar/libs/cal/src/main/java/com/diegonmarcos/superapp/cal/CalModels.kt's
 * class of the same name — IcsParser.kt (also copied into this
 * package, see that file's header comment) constructs it unqualified,
 * so it must exist in this package under this exact name/shape. Kept
 * intentionally free of a `calendarLabel`/`color` field, same as the
 * calendar app's original: those live on [EventCalendarConfig] and are
 * joined in by the caller (NewsBridge.events) at read time, since one
 * calendar's label/color is shared by every one of its events rather
 * than worth repeating per-event in the cache.
 */
data class CalEvent(
    val id: String,
    val calendarId: String,
    val uid: String,
    val title: String,
    val description: String,
    val location: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val allDay: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("calendarId", calendarId)
        put("uid", uid)
        put("title", title)
        put("description", description)
        put("location", location)
        put("startUtcMillis", startUtcMillis)
        put("endUtcMillis", endUtcMillis)
        put("allDay", allDay)
    }

    companion object {
        fun fromJson(o: JSONObject): CalEvent = CalEvent(
            id             = o.optString("id", ""),
            calendarId     = o.optString("calendarId", ""),
            uid            = o.optString("uid", ""),
            title          = o.optString("title", ""),
            description    = o.optString("description", ""),
            location       = o.optString("location", ""),
            startUtcMillis = o.optLong("startUtcMillis", 0L),
            endUtcMillis   = o.optLong("endUtcMillis", 0L),
            allDay         = o.optBoolean("allDay", false),
        )
    }
}

/** One entry from data/events.json's `calendars` array — a public ICS
 *  feed EventsSync keeps in sync locally. Field-for-field the same
 *  shape as ea_cloud-calendar's `CalSubscription` (CalModels.kt),
 *  renamed to avoid implying it's that other app's class (this module
 *  has no dependency on ea_cloud-calendar's code, only on the same
 *  data-file SHAPE by convention). `read_only` is implicit: this
 *  module never writes back to the remote URL, same as the calendar
 *  app's original. */
data class EventCalendarConfig(
    val id: String,
    val label: String,
    val url: String,
    val category: String,
    val color: String,
    val enabled: Boolean,
    val refreshHours: Int,
)

/**
 * Decodes data/events.json (baked into the APP module's
 * BuildConfig.EVENTS_B64) into [EventCalendarConfig]s — same split
 * responsibility as [NewsTopicsConfig]/[NewsSourcesConfig]/[MediaConfig]:
 * libs:news can't reach BuildConfig itself, so callers (NewsBridge)
 * decode the Base64 and hand the raw JSON string here. Mirrors
 * ea_cloud-calendar's `Calendars.parse` (CalModels.kt) exactly,
 * including the `defaults.refresh_hours` fallback.
 */
object EventCalendars {
    @Volatile
    private var cache: List<EventCalendarConfig>? = null

    /** Keys beginning with "_doc" are comments and are ignored, same
     *  convention as every other JSON config file under data/ in this
     *  app. Never throws: a malformed/missing file degrades to an
     *  empty calendar list rather than crashing the app. */
    fun parse(json: String): List<EventCalendarConfig> {
        cache?.let { return it }
        val result = runCatching {
            val root = JSONObject(json)
            val defaults = root.optJSONObject("defaults") ?: JSONObject()
            val defaultRefreshHours = defaults.optInt("refresh_hours", 24)
            val arr: JSONArray = root.optJSONArray("calendars") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) return@mapNotNull null
                EventCalendarConfig(
                    id = id,
                    label = o.optString("name", id),
                    url = o.optString("url", ""),
                    category = o.optString("category", ""),
                    color = o.optString("color", "#888888"),
                    enabled = o.optBoolean("enabled", false),
                    refreshHours = if (o.has("refresh_hours")) o.optInt("refresh_hours", defaultRefreshHours)
                                   else defaultRefreshHours,
                )
            }
        }.getOrDefault(emptyList())
        cache = result
        return result
    }
}

/**
 * A user-saved [CalEvent] snapshot — the shape [SavedEventsStore]
 * persists (see NewsStore.kt-style prefs stores in EventsStore.kt).
 * Unlike [CalEvent], this carries `calendarLabel`/`color` INLINE
 * (denormalized, not joined at read time) — "so a saved event still
 * renders after it ages out of the feed cache" (spec) means it must
 * survive [EventCalendars]/[EventsStore] ever having heard of its
 * calendar again, e.g. after a rebuild drops/renames that calendar
 * entry in data/events.json. A full, standalone, self-describing
 * snapshot is the only way to guarantee that.
 */
data class SavedEvent(
    val id: String,
    val calendarId: String,
    val calendarLabel: String,
    val color: String,
    val title: String,
    val location: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val allDay: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("calendarId", calendarId)
        put("calendarLabel", calendarLabel)
        put("color", color)
        put("title", title)
        put("location", location)
        put("startUtcMillis", startUtcMillis)
        put("endUtcMillis", endUtcMillis)
        put("allDay", allDay)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedEvent = SavedEvent(
            id             = o.optString("id", ""),
            calendarId     = o.optString("calendarId", ""),
            calendarLabel  = o.optString("calendarLabel", ""),
            color          = o.optString("color", "#888888"),
            title          = o.optString("title", ""),
            location       = o.optString("location", ""),
            startUtcMillis = o.optLong("startUtcMillis", 0L),
            endUtcMillis   = o.optLong("endUtcMillis", 0L),
            allDay         = o.optBoolean("allDay", false),
        )

        /** Builds a [SavedEvent] snapshot from a live [CalEvent] plus
         *  the calendar metadata NewsBridge already has in hand at
         *  save time (see [EventCalendarConfig]) — the denormalization
         *  point described in this class's kdoc. */
        fun from(event: CalEvent, calendarLabel: String, color: String): SavedEvent = SavedEvent(
            id = event.id,
            calendarId = event.calendarId,
            calendarLabel = calendarLabel,
            color = color,
            title = event.title,
            location = event.location,
            startUtcMillis = event.startUtcMillis,
            endUtcMillis = event.endUtcMillis,
            allDay = event.allDay,
        )
    }
}
