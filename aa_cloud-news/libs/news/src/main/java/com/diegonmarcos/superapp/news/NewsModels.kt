package com.diegonmarcos.superapp.news

import org.json.JSONObject

/**
 * P0 scaffold: data classes only, ported 1:1 from the existing TypeScript
 * contract at V_front-configs/c-LabTools/news/src/typescript/types.ts (the
 * GDELT-backed news proxy's response shapes). NO networking, NO store, NO
 * sync here yet — that lands in P1, mirroring libs:cal's CalDav.kt /
 * CalendarStore.kt split once this module actually talks to the API.
 */

/** One GDELT article, as returned by /articles. Mirrors TS `GdeltArticle`. */
data class GdeltArticle(
    val url: String,
    val title: String,
    val seendate: String,
    val socialimage: String,
    val domain: String,
    val language: String,
    val sourcecountry: String,
    val tone: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("seendate", seendate)
        put("socialimage", socialimage)
        put("domain", domain)
        put("language", language)
        put("sourcecountry", sourcecountry)
        put("tone", tone)
    }

    companion object {
        fun fromJson(o: JSONObject): GdeltArticle = GdeltArticle(
            url           = o.optString("url", ""),
            title         = o.optString("title", ""),
            seendate      = o.optString("seendate", ""),
            socialimage   = o.optString("socialimage", ""),
            domain        = o.optString("domain", ""),
            language      = o.optString("language", ""),
            sourcecountry = o.optString("sourcecountry", ""),
            tone          = o.optDouble("tone", 0.0),
        )
    }
}

/** One point on a topic's tone timeline, as returned by /timeline.
 *  Mirrors TS `TimelinePoint`. */
data class TimelinePoint(
    val date: String,
    val value: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("value", value)
    }

    companion object {
        fun fromJson(o: JSONObject): TimelinePoint = TimelinePoint(
            date  = o.optString("date", ""),
            value = o.optDouble("value", 0.0),
        )
    }
}

/** One article's tone entry, as returned by /tone. Mirrors TS `ToneEntry`. */
data class ToneEntry(
    val url: String,
    val title: String,
    val tone: Double,
    val domain: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("tone", tone)
        put("domain", domain)
    }

    companion object {
        fun fromJson(o: JSONObject): ToneEntry = ToneEntry(
            url    = o.optString("url", ""),
            title  = o.optString("title", ""),
            tone   = o.optDouble("tone", 0.0),
            domain = o.optString("domain", ""),
        )
    }
}

/** Rolling per-topic summary, as returned by /topics. Mirrors TS
 *  `TopicSummary`. */
data class TopicSummary(
    val topic: String,
    val label: String,
    val articleCount: Int,
    val avgTone: Double,
    val lastFetch: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("topic", topic)
        put("label", label)
        put("articleCount", articleCount)
        put("avgTone", avgTone)
        put("lastFetch", lastFetch)
    }

    companion object {
        fun fromJson(o: JSONObject): TopicSummary = TopicSummary(
            topic        = o.optString("topic", ""),
            label        = o.optString("label", ""),
            articleCount = o.optInt("articleCount", 0),
            avgTone      = o.optDouble("avgTone", 0.0),
            lastFetch    = o.optString("lastFetch", ""),
        )
    }
}
