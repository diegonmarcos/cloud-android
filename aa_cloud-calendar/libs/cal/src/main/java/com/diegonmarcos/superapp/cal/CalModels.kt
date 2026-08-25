package com.diegonmarcos.superapp.cal

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single parsed VEVENT, already resolved to UTC millis. `allDay`
 * events keep midnight-UTC boundaries so the UI can format them by
 * date only, without re-deriving the DATE-vs-DATETIME distinction
 * from the raw ICS text.
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

/** One entry from data/calendars.json — a public ICS feed the engine
 *  keeps in sync locally. `read_only` is implicit: this module never
 *  writes back to the remote URL. */
data class CalSubscription(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    val color: String,
    val enabled: Boolean,
    val refreshHours: Int,
)

/**
 * Decodes data/calendars.json (baked into the APP module's
 * BuildConfig.CALENDARS_B64) into [CalSubscription]s. libs:cal has no
 * dependency on the app module and therefore no access to that
 * BuildConfig field directly — callers decode the Base64 themselves
 * and hand the raw JSON string to [parse]. Parsed results are cached
 * per-process since the underlying data never changes without a
 * rebuild.
 */
object Calendars {
    @Volatile
    private var cache: List<CalSubscription>? = null

    /** Parse `calendars.json`'s raw text (NOT base64) into subscriptions.
     *  Keys beginning with "_doc" are comments and are ignored — they
     *  live only at the object level so plain iteration of the
     *  "calendars" array never sees them. */
    fun parse(json: String): List<CalSubscription> {
        cache?.let { return it }
        val result = runCatching {
            val root = JSONObject(json)
            val defaults = root.optJSONObject("defaults") ?: JSONObject()
            val defaultRefreshHours = defaults.optInt("refresh_hours", 24)
            val arr: JSONArray = root.optJSONArray("calendars") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                CalSubscription(
                    id = id,
                    name = o.optString("name", id),
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
