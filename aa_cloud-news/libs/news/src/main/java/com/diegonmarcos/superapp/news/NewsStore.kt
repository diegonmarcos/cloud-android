package com.diegonmarcos.superapp.news

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed local cache of synced news data, keyed per
 * topic, plus the sync bookkeeping (`lastFetch`) NewsSync needs.
 * Follows the same house style as ea_cloud-calendar's CalendarStore:
 * one prefs file, JSON strings as values, no Room/DB dependency.
 *
 * OFFLINE-FIRST is the whole point: every read here is a pure prefs
 * read with no network involved, so the UI renders instantly from
 * whatever was last synced. [replaceArticles]/[replaceTimeline]/
 * [replaceTone]/[replaceTopicSummaries] are only ever called by
 * NewsSync AFTER a successful fetch — a failed refresh throws before
 * reaching them, so the previous cache is left untouched rather than
 * blanked. See NewsSync.kt.
 */
object NewsStore {
    private const val PREFS = "news_cache"
    private const val ARTICLES_PREFIX = "articles_"
    private const val TIMELINE_PREFIX = "timeline_"
    private const val TONE_PREFIX = "tone_"
    private const val LAST_FETCH_PREFIX = "last_fetch_"
    private const val TOPIC_SUMMARIES_KEY = "topic_summaries"

    // A single topic tops out at data/topics.json's max_articles (50 by
    // default), but a user-raised limit or a misbehaving proxy could
    // return far more. Prefs values are held in memory and written as
    // one blob, so an unbounded response could bloat memory/disk I/O on
    // every read/write — cap per topic per list, same reasoning as
    // CalendarStore.MAX_EVENTS_PER_CALENDAR.
    private const val MAX_ARTICLES_PER_TOPIC = 500
    private const val MAX_TIMELINE_POINTS_PER_TOPIC = 500
    private const val MAX_TONE_ENTRIES_PER_TOPIC = 500

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- articles -----------------------------------------------------

    /** Wholesale swap of one topic's cached articles — GDELT's article
     *  search has no incremental/diff mode, so like CalendarStore this
     *  is always a full replace, never a merge. Newest-first is NOT
     *  enforced here; callers (NewsBridge) sort by `seendate` at read
     *  time since seendate is a zero-padded `yyyyMMdd'T'HHmmss'Z'`
     *  string and therefore already lexicographically sortable. */
    fun replaceArticles(ctx: Context, topic: String, articles: List<GdeltArticle>) {
        val capped = if (articles.size > MAX_ARTICLES_PER_TOPIC) articles.take(MAX_ARTICLES_PER_TOPIC) else articles
        val arr = JSONArray()
        for (a in capped) arr.put(a.toJson())
        prefs(ctx).edit().putString(ARTICLES_PREFIX + topic, arr.toString()).apply()
    }

    fun articlesFor(ctx: Context, topic: String): List<GdeltArticle> {
        val raw = prefs(ctx).getString(ARTICLES_PREFIX + topic, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { GdeltArticle.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    /** Union of cached articles across [topics], for the merged "all
     *  enabled topics" feed. Dedupes by url (the same article can be
     *  returned under more than one query term). */
    fun articlesFor(ctx: Context, topics: List<String>): List<GdeltArticle> {
        val seen = LinkedHashMap<String, GdeltArticle>()
        for (t in topics) {
            for (a in articlesFor(ctx, t)) {
                if (a.url.isNotBlank()) seen.putIfAbsent(a.url, a)
            }
        }
        return seen.values.toList()
    }

    fun clearTopic(ctx: Context, topic: String) {
        prefs(ctx).edit()
            .remove(ARTICLES_PREFIX + topic)
            .remove(TIMELINE_PREFIX + topic)
            .remove(TONE_PREFIX + topic)
            .remove(LAST_FETCH_PREFIX + topic)
            .apply()
    }

    // ---- timeline -------------------------------------------------------

    fun replaceTimeline(ctx: Context, topic: String, points: List<TimelinePoint>) {
        val capped = if (points.size > MAX_TIMELINE_POINTS_PER_TOPIC) points.take(MAX_TIMELINE_POINTS_PER_TOPIC) else points
        val arr = JSONArray()
        for (p in capped) arr.put(p.toJson())
        prefs(ctx).edit().putString(TIMELINE_PREFIX + topic, arr.toString()).apply()
    }

    fun timelineFor(ctx: Context, topic: String): List<TimelinePoint> {
        val raw = prefs(ctx).getString(TIMELINE_PREFIX + topic, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { TimelinePoint.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // ---- tone -----------------------------------------------------------

    fun replaceTone(ctx: Context, topic: String, entries: List<ToneEntry>) {
        val capped = if (entries.size > MAX_TONE_ENTRIES_PER_TOPIC) entries.take(MAX_TONE_ENTRIES_PER_TOPIC) else entries
        val arr = JSONArray()
        for (e in capped) arr.put(e.toJson())
        prefs(ctx).edit().putString(TONE_PREFIX + topic, arr.toString()).apply()
    }

    fun toneFor(ctx: Context, topic: String): List<ToneEntry> {
        val raw = prefs(ctx).getString(TONE_PREFIX + topic, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { ToneEntry.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // ---- topic summaries (server-side /topics, cached wholesale) --------

    fun replaceTopicSummaries(ctx: Context, summaries: List<TopicSummary>) {
        val arr = JSONArray()
        for (s in summaries) arr.put(s.toJson())
        prefs(ctx).edit().putString(TOPIC_SUMMARIES_KEY, arr.toString()).apply()
    }

    fun topicSummaries(ctx: Context): List<TopicSummary> {
        val raw = prefs(ctx).getString(TOPIC_SUMMARIES_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { TopicSummary.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // ---- last-fetch bookkeeping ------------------------------------------

    fun lastFetch(ctx: Context, topic: String): Long =
        prefs(ctx).getLong(LAST_FETCH_PREFIX + topic, 0L)

    fun setLastFetch(ctx: Context, topic: String, millis: Long) {
        prefs(ctx).edit().putLong(LAST_FETCH_PREFIX + topic, millis).apply()
    }
}

/** One topic merged with its runtime enabled/custom state — the shape
 *  [NewsTopicsStore.effective] returns and [NewsSync.syncAll] consumes. */
data class NewsTopicState(
    val topic: String,
    val label: String,
    val enabled: Boolean,
    val custom: Boolean,
)

/**
 * Runtime overlay on top of the baked-in data/topics.json topic list:
 * which baked defaults the user removed, which topics (baked or
 * custom) are disabled, and any topics the user added beyond the
 * defaults. Separate SharedPreferences file from [NewsStore] since
 * this is small config state, not the (much larger) per-topic article
 * cache — mirrors how CalBridge keeps CaldavPrefs in its own file
 * apart from CalendarStore's event cache.
 *
 * Callers always pass in the baked [NewsTopicConfig] list decoded from
 * BuildConfig.TOPICS_B64 (this module can't reach BuildConfig itself —
 * same reasoning as `Calendars.parse` in CalModels.kt).
 */
object NewsTopicsStore {
    private const val PREFS = "news_topics_overlay"
    private const val KEY_CUSTOM = "custom_topics"
    private const val KEY_REMOVED_DEFAULTS = "removed_defaults"
    private const val KEY_DISABLED = "disabled_topics"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Baked defaults (minus any the user removed) followed by any
     *  user-added custom topics, each carrying its current
     *  enabled/disabled state. Order is stable: baked order first,
     *  then custom topics in the order they were added. */
    fun effective(ctx: Context, baked: List<NewsTopicConfig>): List<NewsTopicState> {
        val removed = removedDefaults(ctx)
        val disabled = disabledSet(ctx)
        val bakedStates = baked
            .filter { it.topic !in removed }
            .map { NewsTopicState(it.topic, it.label, it.topic !in disabled, custom = false) }
        val customStates = customTopics(ctx)
            .map { NewsTopicState(it.topic, it.label, it.topic !in disabled, custom = true) }
        return bakedStates + customStates
    }

    fun setEnabled(ctx: Context, topic: String, enabled: Boolean) {
        val set = disabledSet(ctx).toMutableSet()
        if (enabled) set.remove(topic) else set.add(topic)
        setDisabledSet(ctx, set)
    }

    /** Adds a brand-new topic, or un-removes a previously-removed
     *  baked default (its original baked label wins in that case — a
     *  removed default is restored, not renamed). Returns an error
     *  string, or "" on success. */
    fun addTopic(ctx: Context, topic: String, label: String, baked: List<NewsTopicConfig>): String {
        val key = topic.trim()
        if (key.isEmpty()) return "topic is required"

        val bakedMatch = baked.firstOrNull { it.topic == key }
        if (bakedMatch != null) {
            val removed = removedDefaults(ctx)
            if (key in removed) {
                setRemovedDefaults(ctx, removed - key)
                return ""
            }
            return "topic already exists"
        }

        if (customTopics(ctx).any { it.topic == key }) return "topic already exists"

        val cleanLabel = label.trim().ifEmpty { key }
        setCustomTopics(ctx, customTopics(ctx) + NewsTopicConfig(key, cleanLabel))
        return ""
    }

    /** Removes a custom topic outright, or hides a baked default via
     *  [KEY_REMOVED_DEFAULTS] (topics.json itself is never touched at
     *  runtime). Returns an error string, or "" on success. */
    fun removeTopic(ctx: Context, topic: String, baked: List<NewsTopicConfig>): String {
        val key = topic.trim()
        if (key.isEmpty()) return "topic is required"

        val customs = customTopics(ctx)
        if (customs.any { it.topic == key }) {
            setCustomTopics(ctx, customs.filterNot { it.topic == key })
            clearDisabled(ctx, key)
            return ""
        }

        if (baked.any { it.topic == key }) {
            setRemovedDefaults(ctx, removedDefaults(ctx) + key)
            clearDisabled(ctx, key)
            return ""
        }

        return "topic not found"
    }

    private fun clearDisabled(ctx: Context, topic: String) {
        val set = disabledSet(ctx)
        if (topic in set) setDisabledSet(ctx, set - topic)
    }

    private fun customTopics(ctx: Context): List<NewsTopicConfig> {
        val raw = prefs(ctx).getString(KEY_CUSTOM, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("topic", "")
                if (t.isEmpty()) null else NewsTopicConfig(t, o.optString("label", t))
            }
        }.getOrDefault(emptyList())
    }

    private fun setCustomTopics(ctx: Context, topics: List<NewsTopicConfig>) {
        val arr = JSONArray()
        for (t in topics) arr.put(JSONObject().apply { put("topic", t.topic); put("label", t.label) })
        prefs(ctx).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun removedDefaults(ctx: Context): Set<String> = stringSet(ctx, KEY_REMOVED_DEFAULTS)
    private fun setRemovedDefaults(ctx: Context, set: Set<String>) = setStringSet(ctx, KEY_REMOVED_DEFAULTS, set)
    private fun disabledSet(ctx: Context): Set<String> = stringSet(ctx, KEY_DISABLED)
    private fun setDisabledSet(ctx: Context, set: Set<String>) = setStringSet(ctx, KEY_DISABLED, set)

    private fun stringSet(ctx: Context, key: String): Set<String> {
        val raw = prefs(ctx).getString(key, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotEmpty() } }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun setStringSet(ctx: Context, key: String, set: Set<String>) {
        val arr = JSONArray()
        for (s in set) arr.put(s)
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }
}

/**
 * Runtime override of data/topics.json's `api` block — lets the user
 * point the app at the WireGuard-mesh HTTP host (`http://news-gdelt:3019`)
 * instead of the public HTTPS gateway, or vice-versa. Plain
 * SharedPreferences, unlike CalBridge's CaldavPrefs: there are no
 * credentials in this config, just a base URL and two integers, so
 * EncryptedSharedPreferences would be pure overhead here.
 */
object NewsConfigStore {
    private const val PREFS = "news_config"
    private const val KEY_BASE = "base"
    private const val KEY_REFRESH_SECONDS = "refresh_seconds"
    private const val KEY_MAX_ARTICLES = "max_articles"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [baked] (decoded from BuildConfig.TOPICS_B64 by the caller) is
     *  the default for any field the user hasn't overridden. */
    fun effective(ctx: Context, baked: NewsApiConfig): NewsApiConfig {
        val p = prefs(ctx)
        val base = p.getString(KEY_BASE, null)?.takeIf { it.isNotBlank() } ?: baked.base
        val refreshSeconds = if (p.contains(KEY_REFRESH_SECONDS)) {
            p.getInt(KEY_REFRESH_SECONDS, baked.refreshSeconds)
        } else baked.refreshSeconds
        val maxArticles = if (p.contains(KEY_MAX_ARTICLES)) {
            p.getInt(KEY_MAX_ARTICLES, baked.maxArticles)
        } else baked.maxArticles
        return NewsApiConfig(base, refreshSeconds, maxArticles)
    }

    /** Any null parameter leaves that field's existing override (or
     *  baked default, if never overridden) unchanged. */
    fun setOverride(ctx: Context, base: String?, refreshSeconds: Int?, maxArticles: Int?) {
        val editor = prefs(ctx).edit()
        if (base != null) editor.putString(KEY_BASE, base)
        if (refreshSeconds != null) editor.putInt(KEY_REFRESH_SECONDS, refreshSeconds)
        if (maxArticles != null) editor.putInt(KEY_MAX_ARTICLES, maxArticles)
        editor.apply()
    }
}
