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
 *
 * Every cache entry is keyed by (source, topic), not just topic — see
 * [cacheKey]. `source` is a data/sources.json id (gdelt-self,
 * google-news, bbc-world, ...). For a non-query-driven rss source
 * (bbc-world, guardian-world, hackernews, ntfy-self, ...) this `topic`
 * parameter IS a CHANNEL id (see [NewsChannelConfig]) — e.g. bbc-world
 * caches under keys like `bbc-world::world`/`bbc-world::business`, one
 * per declared sub-feed, rather than a single `bbc-world::bbc-world`
 * blob for the whole source. For a query-driven source (gdelt-self,
 * google-news) a channel IS a topic (same id, by design — see
 * [com.diegonmarcos.cloudnews.NewsBridge].channelsForSource), so this
 * collapses to exactly the pre-channels behaviour with zero code
 * changes needed here: the (source, channel) model IS the (source,
 * topic) model, just with the second half of the key renamed in the
 * caller's head, not a parallel scheme. Namespacing by source is what
 * lets [com.diegonmarcos.cloudnews.NewsBridge].setSource flip the
 * active source without blowing away whatever was already cached for
 * the source the user is switching AWAY from — switching back later
 * finds that cache still warm (and, per source, on whichever channel
 * was last active there — see NewsBridge's ChannelStore).
 */
object NewsStore {
    private const val PREFS = "news_cache"
    private const val ARTICLES_PREFIX = "articles_"
    private const val TIMELINE_PREFIX = "timeline_"
    private const val TONE_PREFIX = "tone_"
    private const val LAST_FETCH_PREFIX = "last_fetch_"
    private const val TOPIC_SUMMARIES_PREFIX = "topic_summaries_"

    /** `::` can't appear in a source id (data/sources.json ids are
     *  plain identifiers) or in a GDELT query-term topic in practice,
     *  so it's a safe separator without needing to escape either half. */
    private fun cacheKey(prefix: String, source: String, topic: String): String = "$prefix$source::$topic"

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
    fun replaceArticles(ctx: Context, source: String, topic: String, articles: List<GdeltArticle>) {
        val capped = if (articles.size > MAX_ARTICLES_PER_TOPIC) articles.take(MAX_ARTICLES_PER_TOPIC) else articles
        val arr = JSONArray()
        for (a in capped) arr.put(a.toJson())
        prefs(ctx).edit().putString(cacheKey(ARTICLES_PREFIX, source, topic), arr.toString()).apply()
    }

    fun articlesFor(ctx: Context, source: String, topic: String): List<GdeltArticle> {
        val raw = prefs(ctx).getString(cacheKey(ARTICLES_PREFIX, source, topic), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { GdeltArticle.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    /** Union of cached articles across [topics] for one [source], for
     *  the merged "all enabled topics" feed. Dedupes by url (the same
     *  article can be returned under more than one query term). */
    fun articlesFor(ctx: Context, source: String, topics: List<String>): List<GdeltArticle> {
        val seen = LinkedHashMap<String, GdeltArticle>()
        for (t in topics) {
            for (a in articlesFor(ctx, source, t)) {
                if (a.url.isNotBlank()) seen.putIfAbsent(a.url, a)
            }
        }
        return seen.values.toList()
    }

    fun clearTopic(ctx: Context, source: String, topic: String) {
        prefs(ctx).edit()
            .remove(cacheKey(ARTICLES_PREFIX, source, topic))
            .remove(cacheKey(TIMELINE_PREFIX, source, topic))
            .remove(cacheKey(TONE_PREFIX, source, topic))
            .remove(cacheKey(LAST_FETCH_PREFIX, source, topic))
            .apply()
    }

    // ---- timeline -------------------------------------------------------

    fun replaceTimeline(ctx: Context, source: String, topic: String, points: List<TimelinePoint>) {
        val capped = if (points.size > MAX_TIMELINE_POINTS_PER_TOPIC) points.take(MAX_TIMELINE_POINTS_PER_TOPIC) else points
        val arr = JSONArray()
        for (p in capped) arr.put(p.toJson())
        prefs(ctx).edit().putString(cacheKey(TIMELINE_PREFIX, source, topic), arr.toString()).apply()
    }

    /** No rss source ever writes a timeline cache entry (there's no
     *  endpoint for it) so this naturally returns empty for one — see
     *  [com.diegonmarcos.cloudnews.NewsBridge].timeline's capability
     *  gate for the UI-facing half of that contract. */
    fun timelineFor(ctx: Context, source: String, topic: String): List<TimelinePoint> {
        val raw = prefs(ctx).getString(cacheKey(TIMELINE_PREFIX, source, topic), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { TimelinePoint.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // ---- tone -----------------------------------------------------------

    fun replaceTone(ctx: Context, source: String, topic: String, entries: List<ToneEntry>) {
        val capped = if (entries.size > MAX_TONE_ENTRIES_PER_TOPIC) entries.take(MAX_TONE_ENTRIES_PER_TOPIC) else entries
        val arr = JSONArray()
        for (e in capped) arr.put(e.toJson())
        prefs(ctx).edit().putString(cacheKey(TONE_PREFIX, source, topic), arr.toString()).apply()
    }

    /** Same "never written for an rss source" reasoning as
     *  [timelineFor]. */
    fun toneFor(ctx: Context, source: String, topic: String): List<ToneEntry> {
        val raw = prefs(ctx).getString(cacheKey(TONE_PREFIX, source, topic), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { ToneEntry.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // ---- topic summaries (server-side /topics, cached wholesale) --------

    fun replaceTopicSummaries(ctx: Context, source: String, summaries: List<TopicSummary>) {
        val arr = JSONArray()
        for (s in summaries) arr.put(s.toJson())
        prefs(ctx).edit().putString(TOPIC_SUMMARIES_PREFIX + source, arr.toString()).apply()
    }

    /** Only ever populated for `kind: "gdelt"` sources (the only ones
     *  with a server-side /topics summary endpoint) — an rss source's
     *  key is simply never written, so this returns empty for one. */
    fun topicSummaries(ctx: Context, source: String): List<TopicSummary> {
        val raw = prefs(ctx).getString(TOPIC_SUMMARIES_PREFIX + source, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { TopicSummary.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    // ---- last-fetch bookkeeping ------------------------------------------

    fun lastFetch(ctx: Context, source: String, topic: String): Long =
        prefs(ctx).getLong(cacheKey(LAST_FETCH_PREFIX, source, topic), 0L)

    fun setLastFetch(ctx: Context, source: String, topic: String, millis: Long) {
        prefs(ctx).edit().putLong(cacheKey(LAST_FETCH_PREFIX, source, topic), millis).apply()
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

/**
 * Persists which entry from data/sources.json (BuildConfig.SOURCES_B64,
 * decoded by NewsBridge — same split as [NewsConfigStore]) the user has
 * picked as the active article source: the self-hosted GDELT proxy, or
 * one of the public RSS feeds. Its own prefs file, same reasoning as
 * [NewsConfigStore]: this is one string, not credentials, so plain
 * SharedPreferences is enough. Defaults to data/sources.json's own
 * `default_source` (normally "gdelt-self") until the user explicitly
 * calls [setActive] — see NewsBridge.setSource.
 */
object SourceStore {
    private const val PREFS = "news_source"
    private const val KEY_ACTIVE = "active_source"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun active(ctx: Context, defaultId: String): String =
        prefs(ctx).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() } ?: defaultId

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }
}

/**
 * Persists which CHANNEL within each source (see [NewsChannelConfig])
 * the user last picked — one entry PER SOURCE ID, not a single global
 * value, which is what lets switching source away and back remember
 * where the user was on each independently (switch to Guardian's
 * "Business" channel, hop to Hacker News, come back to Guardian: still
 * on "Business"). [NewsBridge]'s activeChannelId falls back to a
 * source's first channel whenever nothing has been persisted for it
 * yet (brand-new source, or one whose previously-active channel no
 * longer exists after a rebuild). Own prefs file, same one-small-value
 * reasoning as [SourceStore] and [NewsConfigStore].
 */
object ChannelStore {
    private const val PREFS = "news_channel"
    private const val KEY_PREFIX = "active_channel_"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun active(ctx: Context, sourceId: String, defaultId: String): String =
        prefs(ctx).getString(KEY_PREFIX + sourceId, null)?.takeIf { it.isNotBlank() } ?: defaultId

    fun setActive(ctx: Context, sourceId: String, channelId: String) {
        prefs(ctx).edit().putString(KEY_PREFIX + sourceId, channelId).apply()
    }
}

/**
 * On-disk cache of the last successfully-DISCOVERED channel list for a
 * `dynamic_channels: true` source (ntfy-self) — see NewsModels.kt's
 * [DynamicChannels] for the discovery parse itself and NewsSync.kt's
 * syncRssDynamic for when this gets written. Keyed by source id (not
 * hardcoded to ntfy-self) in case a second dynamic-channel source is
 * ever added.
 *
 * REPLACE-ONLY-ON-SUCCESS, same rule as every other cache in this file:
 * a failed/unreachable/unparseable discovery fetch leaves whatever was
 * last cached here untouched rather than blanking it — an offline user
 * still sees their ~142 channels from the last time discovery
 * succeeded, they just don't get NEW ones until connectivity returns.
 * [channelsFor] naturally returns empty for a source that has never
 * once discovered successfully, which NewsBridge.channelsForSource
 * covers with its own last-resort single-channel fallback.
 */
object DynamicChannelStore {
    private const val PREFS = "news_dynamic_channels"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun replace(ctx: Context, sourceId: String, channels: List<NewsChannelConfig>) {
        val arr = JSONArray()
        for (c in channels) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("label", c.label)
                put("url", c.url)
                put("group", c.group ?: JSONObject.NULL)
                put("path", c.path ?: JSONObject.NULL)
            })
        }
        prefs(ctx).edit().putString(sourceId, arr.toString()).apply()
    }

    fun channelsFor(ctx: Context, sourceId: String): List<NewsChannelConfig> {
        val raw = prefs(ctx).getString(sourceId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                NewsChannelConfig(
                    id = id,
                    label = o.optString("label", id),
                    url = o.optString("url", ""),
                    group = if (o.has("group") && !o.isNull("group")) o.optString("group").takeIf { it.isNotBlank() } else null,
                    // "path" is a newer field (added alongside the real
                    // rss-gateway schema fix) — absent entirely on a
                    // cache blob written by an older build, which
                    // o.has() correctly reads as null rather than "".
                    path = if (o.has("path") && !o.isNull("path")) o.optString("path").takeIf { it.isNotBlank() } else null,
                )
            }
        }.getOrDefault(emptyList())
    }
}
