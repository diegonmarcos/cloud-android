package com.diegonmarcos.superapp.news

import org.json.JSONArray
import org.json.JSONObject

/**
 * Response-shape data classes, ported 1:1 from the existing TypeScript
 * contract at V_front-configs/c-LabTools/news/src/typescript/types.ts
 * (the GDELT-backed news proxy's response shapes), plus the
 * data/topics.json config models below. P1: networking lives in
 * NewsApi.kt, the on-disk cache in NewsStore.kt, the refresh engine in
 * NewsSync.kt, and local bookmarks in SavedStore.kt — mirroring
 * libs:cal's CalDav.kt / CalendarStore.kt split.
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

/** One entry from data/topics.json's `topics` array — a GDELT query
 *  term the app tracks. Mirrors TS `DEFAULT_TOPICS`. Unlike
 *  [com.diegonmarcos.superapp.cal.CalSubscription] there is no
 *  `enabled` field here: topics.json ships no per-topic enable state,
 *  that's purely a runtime user choice layered on top by
 *  `NewsTopicsStore` in NewsStore.kt. */
data class NewsTopicConfig(
    val topic: String,
    val label: String,
)

/** The `api` section of data/topics.json — base URL + polling knobs.
 *  Mirrors TS `API_BASE`/`REFRESH_INTERVAL`/`MAX_ARTICLES` from
 *  config.ts. `base` defaults to the public HTTPS gateway; a user can
 *  override it at runtime (e.g. to the WireGuard-mesh HTTP host) via
 *  `NewsConfigStore` — see NewsStore.kt. */
data class NewsApiConfig(
    val base: String,
    val refreshSeconds: Int,
    val maxArticles: Int,
)

/**
 * Decodes data/topics.json (baked into the APP module's
 * BuildConfig.TOPICS_B64) into [NewsTopicConfig]s / [NewsApiConfig].
 * libs:news has no dependency on the app module and therefore no
 * access to that BuildConfig field directly — callers (NewsBridge)
 * decode the Base64 themselves and hand the raw JSON string to
 * [parseTopics]/[parseApi]. Mirrors `Calendars` in
 * ea_cloud-calendar/libs/cal/CalModels.kt. Parsed results are cached
 * per-process since the underlying data never changes without a
 * rebuild.
 */
object NewsTopicsConfig {
    @Volatile
    private var topicsCache: List<NewsTopicConfig>? = null

    @Volatile
    private var apiCache: NewsApiConfig? = null

    /** Keys beginning with "_doc" are comments and are ignored — they
     *  live only at the object level so plain iteration of the
     *  "topics" array never sees them. Never throws: a malformed file
     *  degrades to an empty topic list rather than crashing the app. */
    fun parseTopics(json: String): List<NewsTopicConfig> {
        topicsCache?.let { return it }
        val result = runCatching {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("topics") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val topic = o.optString("topic", "").trim()
                if (topic.isEmpty()) return@mapNotNull null
                NewsTopicConfig(topic = topic, label = o.optString("label", topic))
            }
        }.getOrDefault(emptyList())
        topicsCache = result
        return result
    }

    /** Falls back to the public HTTPS gateway / TS defaults
     *  (config.ts: REFRESH_INTERVAL=60s, MAX_ARTICLES=50) if the `api`
     *  object is missing or malformed — never throws. */
    fun parseApi(json: String): NewsApiConfig {
        apiCache?.let { return it }
        val result = runCatching {
            val root = JSONObject(json)
            val api = root.optJSONObject("api") ?: JSONObject()
            NewsApiConfig(
                base = api.optString("base", "https://api.diegonmarcos.com/news"),
                refreshSeconds = api.optInt("refresh_seconds", 60),
                maxArticles = api.optInt("max_articles", 50),
            )
        }.getOrDefault(NewsApiConfig("https://api.diegonmarcos.com/news", 60, 50))
        apiCache = result
        return result
    }
}
