package com.diegonmarcos.cloudnews

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import com.diegonmarcos.superapp.news.ChannelStore
import com.diegonmarcos.superapp.news.DynamicChannelStore
import com.diegonmarcos.superapp.news.GdeltArticle
import com.diegonmarcos.superapp.news.NewsApiConfig
import com.diegonmarcos.superapp.news.NewsChannelConfig
import com.diegonmarcos.superapp.news.NewsConfigStore
import com.diegonmarcos.superapp.news.NewsSourceConfig
import com.diegonmarcos.superapp.news.NewsSourcesConfig
import com.diegonmarcos.superapp.news.NewsStore
import com.diegonmarcos.superapp.news.NewsSync
import com.diegonmarcos.superapp.news.NewsTopicConfig
import com.diegonmarcos.superapp.news.NewsTopicState
import com.diegonmarcos.superapp.news.NewsTopicsConfig
import com.diegonmarcos.superapp.news.NewsTopicsStore
import com.diegonmarcos.superapp.news.SavedStore
import com.diegonmarcos.superapp.news.SourceStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * JS bridge exposed to news.html as `window.NewsBridge`.
 *
 * SECURITY: this bridge is only safe to attach because the WebView it
 * is registered on loads a LOCAL asset we control
 * (file:///android_asset/news.html) — see MainActivity. Never attach a
 * JavascriptInterface bridge to a WebView that can navigate to
 * remote/attacker-controlled content: any page loaded there would gain
 * the same Java-callable surface as our own UI. (Same note as CalBridge
 * in ea_cloud-calendar.)
 *
 * Every method returns a JSON STRING (never a raw object) and every
 * epoch-millis value crosses the bridge as a STRING, same convention
 * as CalBridge: JS numbers on this bridge are doubles and would lose
 * precision on a 64-bit millis value. Network work (refresh) runs on
 * a background executor with @Volatile status fields the WebView polls
 * — this bridge must never block the WebView's JS thread on I/O.
 */
class NewsBridge(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var syncRunning = false
    @Volatile private var lastOk = 0
    @Volatile private var lastFailed = 0
    @Volatile private var lastMessages: List<String> = emptyList()
    @Volatile private var lastFetchMillis = 0L

    // ---- baked config (data/topics.json -> BuildConfig.TOPICS_B64) ------

    private fun bakedJson(): String =
        String(Base64.decode(BuildConfig.TOPICS_B64, Base64.DEFAULT), Charsets.UTF_8)

    private fun bakedTopics(): List<NewsTopicConfig> = NewsTopicsConfig.parseTopics(bakedJson())

    private fun bakedApi(): NewsApiConfig = NewsTopicsConfig.parseApi(bakedJson())

    private fun effectiveConfig(): NewsApiConfig = NewsConfigStore.effective(ctx, bakedApi())

    // ---- sources (data/sources.json -> BuildConfig.SOURCES_B64) -----------

    private fun bakedSourcesJson(): String =
        String(Base64.decode(BuildConfig.SOURCES_B64, Base64.DEFAULT), Charsets.UTF_8)

    private fun bakedSources(): List<NewsSourceConfig> = NewsSourcesConfig.parseSources(bakedSourcesJson())

    private fun bakedDefaultSource(): String = NewsSourcesConfig.parseDefaultSource(bakedSourcesJson())

    private fun activeSourceId(): String = SourceStore.active(ctx, bakedDefaultSource())

    /** [bakedSources] always has at least one entry (its own fallback —
     *  see NewsSourcesConfig.parseSources), so `.first()` below never
     *  hits an empty list; it only fires if the persisted active id no
     *  longer exists in a rebuilt sources.json (a source was removed
     *  from the config after the user picked it). */
    private fun activeSourceConfig(): NewsSourceConfig {
        val sources = bakedSources()
        val id = activeSourceId()
        return sources.firstOrNull { it.id == id } ?: sources.first()
    }

    /** The topic list a refresh/topics call should use for [source]:
     *  the normal user-configurable topic list (baked defaults + custom
     *  topics + enabled/disabled overlay — same list regardless of
     *  source) for `gdelt` and query-driven `rss` sources, since both
     *  take a query term per topic; a SINGLE synthetic topic — the
     *  source's currently ACTIVE CHANNEL — for a fixed or
     *  dynamic-channel rss source, since it has no query term to vary
     *  and its cache is keyed by (source, channel) same as everything
     *  else (see NewsStore's cacheKey kdoc: "channel" IS "topic" here,
     *  channel==topic collapses naturally). */
    private fun effectiveTopicsForSource(source: NewsSourceConfig): List<NewsTopicState> =
        if (source.isRss && !source.queryDriven) {
            val channelId = activeChannelId(source)
            val label = channelsForSource(source).firstOrNull { it.id == channelId }?.label ?: source.label
            listOf(NewsTopicState(topic = channelId, label = label, enabled = true, custom = false))
        } else {
            NewsTopicsStore.effective(ctx, bakedTopics())
        }

    // ---- channels -----------------------------------------------------------

    /** [source]'s channel list for the UI's second nav row — ALWAYS
     *  non-empty (falls back to a single channel built from [source]'s
     *  own id/label/url as an absolute last resort, so a brand-new or
     *  not-yet-discovered source is never left with nothing to pick).
     *  Three populating strategies, per [NewsChannelConfig]'s kdoc:
     *   - query-driven (gdelt-self, google-news): synthesized 1:1 from
     *     the LIVE topics store (baked + custom, in effective order) —
     *     NOT the baked topics.json list alone — so adding/removing a
     *     topic in the Topics tab is reflected immediately, with zero
     *     duplication of the topic list into data/sources.json. Each
     *     channel's id IS the topic id.
     *   - dynamic-channel (ntfy-self): the last successfully-discovered
     *     list from [DynamicChannelStore] (refreshed by NewsSync's
     *     syncRssDynamic on every [refresh]).
     *   - fixed rss (bbc-world, guardian-world, ...): [source]'s own
     *     declared `channels`, already guaranteed non-empty by
     *     [NewsSourcesConfig.parseSources]. */
    private fun channelsForSource(source: NewsSourceConfig): List<NewsChannelConfig> {
        val channels = when {
            source.queryDriven -> NewsTopicsStore.effective(ctx, bakedTopics())
                .map { NewsChannelConfig(id = it.topic, label = it.label) }
            source.dynamicChannels -> DynamicChannelStore.channelsFor(ctx, source.id)
            else -> source.channels
        }
        return channels.ifEmpty { listOf(NewsChannelConfig(id = source.id, label = source.label, url = source.url)) }
    }

    /** The channel a refresh()/articles("") call should use: the
     *  per-source persisted choice (see [ChannelStore]), defaulting to
     *  [source]'s first channel — never blank, since
     *  [channelsForSource] always returns at least one entry. */
    private fun activeChannelId(source: NewsSourceConfig): String {
        val fallback = channelsForSource(source).first().id
        return ChannelStore.active(ctx, source.id, fallback)
    }

    /** `channels` is ALWAYS populated (never an empty array) — see
     *  [channelsForSource] — so the UI's second nav row always has
     *  something to render the moment a source is picked. */
    @JavascriptInterface
    fun sources(): String {
        val arr = JSONArray()
        for (s in bakedSources()) {
            val caps = JSONArray()
            for (c in s.capabilities) caps.put(c)
            val channelsArr = JSONArray()
            for (c in channelsForSource(s)) {
                channelsArr.put(JSONObject().apply {
                    put("id", c.id)
                    put("label", c.label)
                    // Optional — only ever set for a dynamic-channel
                    // (ntfy-self) entry whose discovery payload itself
                    // carried a group/category field. Omitted entirely
                    // (not JSON null) when absent, so `channel.group` on
                    // the JS side reads as plain `undefined`.
                    if (!c.group.isNullOrBlank()) put("group", c.group)
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("kind", s.kind)
                put("queryDriven", s.queryDriven)
                put("capabilities", caps)
                put("requiresMesh", s.requiresMesh)
                put("channels", channelsArr)
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun activeSource(): String = JSONObject().apply { put("id", activeSourceId()) }.toString()

    @JavascriptInterface
    fun setSource(id: String): String {
        val key = id.trim()
        if (key.isEmpty()) return okErr(false, "id is required")
        val match = bakedSources().firstOrNull { it.id == key } ?: return okErr(false, "unknown source id")
        SourceStore.setActive(ctx, match.id)
        return okErr(true, "")
    }

    /** The active source's active channel — see [activeChannelId]. */
    @JavascriptInterface
    fun activeChannel(): String =
        JSONObject().apply { put("id", activeChannelId(activeSourceConfig())) }.toString()

    /** Persists [id] as the active source's active channel — see
     *  [ChannelStore]. Rejects an id that isn't one of the active
     *  source's current [channelsForSource] (typo, stale channel from
     *  before a rebuild/re-discovery, id belonging to a different
     *  source, ...) rather than silently accepting an unfetchable
     *  channel. */
    @JavascriptInterface
    fun setChannel(id: String): String {
        val key = id.trim()
        if (key.isEmpty()) return okErr(false, "id is required")
        val source = activeSourceConfig()
        if (channelsForSource(source).none { it.id == key }) return okErr(false, "unknown channel id")
        ChannelStore.setActive(ctx, source.id, key)
        return okErr(true, "")
    }

    // ---- topics -----------------------------------------------------------

    /** Every method below OPERATES ON THE ACTIVE SOURCE
     *  ([activeSourceConfig]) — same JS-facing signatures as before
     *  sources.json existed, but reads/writes NewsStore under the
     *  active source's id, and (for a non-query-driven rss source)
     *  reports a single synthetic topic — that source's ACTIVE
     *  CHANNEL — instead of the user's GDELT/query topic list. */
    @JavascriptInterface
    fun topics(): String {
        val source = activeSourceConfig()
        val effective = effectiveTopicsForSource(source)
        val summariesByTopic = if (source.isGdelt) {
            NewsStore.topicSummaries(ctx, source.id).associateBy { it.topic }
        } else emptyMap()
        val arr = JSONArray()
        for (state in effective) {
            val summary = summariesByTopic[state.topic]
            val cachedArticles = if (summary == null) NewsStore.articlesFor(ctx, source.id, state.topic) else emptyList()
            val articleCount = summary?.articleCount ?: cachedArticles.size
            // No 0.0 lie for a source that never measured tone (see
            // GdeltArticle.tone's kdoc) — omit avgTone entirely (JSON
            // null) rather than average an empty/all-null list into 0.
            val cachedTones = cachedArticles.mapNotNull { it.tone }
            val avgTone: Double? = summary?.avgTone
                ?: (if (source.hasTone && cachedTones.isNotEmpty()) cachedTones.average() else null)
            arr.put(JSONObject().apply {
                put("topic", state.topic)
                put("label", state.label)
                put("articleCount", articleCount)
                put("avgTone", avgTone ?: JSONObject.NULL)
                put("lastFetch", NewsStore.lastFetch(ctx, source.id, state.topic).toString())
                put("enabled", state.enabled)
            })
        }
        return arr.toString()
    }

    /** `channel` (the former `topic` param — same bridge slot, new
     *  meaning: a CHANNEL id, see the class kdoc) "" means the active
     *  source's active channel (see [activeChannelId]); for a
     *  query-driven source channel==topic so this is exactly the same
     *  cache lookup it always was, just for one topic/channel instead
     *  of a merge across every enabled one. Newest first (seendate is a
     *  zero-padded string for every source — RssParser reformats rss
     *  dates into the same shape GDELT uses — so a plain descending
     *  string sort is already chronological, see
     *  NewsStore.replaceArticles). limit falls back to the effective
     *  config's maxArticles if blank/unparseable. */
    @JavascriptInterface
    fun articles(channel: String, limit: String): String {
        val source = activeSourceConfig()
        val limitInt = limit.toIntOrNull()?.takeIf { it > 0 } ?: effectiveConfig().maxArticles
        val chanId = channel.ifBlank { activeChannelId(source) }
        val list = NewsStore.articlesFor(ctx, source.id, chanId)
        val sorted = list.sortedByDescending { it.seendate }.take(limitInt)
        val arr = JSONArray()
        for (a in sorted) arr.put(a.toJson())
        return arr.toString()
    }

    /** Empty for any source lacking "timeline" in its capabilities
     *  (every rss source — see data/sources.json) — checked explicitly
     *  here, on top of the cache key simply never being written for
     *  one, so the capability is enforced even if a stale/leftover key
     *  somehow existed. The UI is expected to check [sources]'
     *  capabilities up front and skip asking at all, per the class
     *  contract, but this is the hard guarantee either way. */
    @JavascriptInterface
    fun timeline(topic: String): String {
        val arr = JSONArray()
        if (topic.isBlank()) return arr.toString()
        val source = activeSourceConfig()
        if (!source.hasTimeline) return arr.toString()
        for (p in NewsStore.timelineFor(ctx, source.id, topic)) arr.put(p.toJson())
        return arr.toString()
    }

    /** Same reasoning as [timeline], gated on "tone" instead. */
    @JavascriptInterface
    fun tone(topic: String): String {
        val arr = JSONArray()
        if (topic.isBlank()) return arr.toString()
        val source = activeSourceConfig()
        if (!source.hasTone) return arr.toString()
        for (t in NewsStore.toneFor(ctx, source.id, topic)) arr.put(t.toJson())
        return arr.toString()
    }

    /** enabled is "true"/"false" (bridge string convention, see
     *  [com.diegonmarcos.cloudcalendar.CalBridge.events] for precedent
     *  in the sibling app). */
    @JavascriptInterface
    fun setTopicEnabled(topic: String, enabled: String): String {
        if (topic.isBlank()) return okErr(false, "topic is required")
        NewsTopicsStore.setEnabled(ctx, topic, enabled == "true")
        return okErr(true, "")
    }

    @JavascriptInterface
    fun addTopic(topic: String, label: String): String {
        val error = NewsTopicsStore.addTopic(ctx, topic, label, bakedTopics())
        return okErr(error.isEmpty(), error)
    }

    /** Removing a topic drops its cache under EVERY source (not just
     *  the active one) — a topic disabled/removed here is meant to
     *  stop being tracked everywhere, and each source has its own
     *  namespaced cache entry for it (see NewsStore's cacheKey kdoc). */
    @JavascriptInterface
    fun removeTopic(topic: String): String {
        val error = NewsTopicsStore.removeTopic(ctx, topic, bakedTopics())
        if (error.isEmpty()) {
            for (s in bakedSources()) NewsStore.clearTopic(ctx, s.id, topic)
        }
        return okErr(error.isEmpty(), error)
    }

    // ---- refresh ------------------------------------------------------------

    @JavascriptInterface
    fun refresh(): String {
        if (!syncRunning) {
            syncRunning = true
            executor.execute {
                try {
                    val cfg = effectiveConfig()
                    val source = activeSourceConfig()
                    val topics = effectiveTopicsForSource(source)
                    val channel = activeChannelId(source)
                    val report = NewsSync.syncAll(ctx, source, topics, cfg.base, cfg.maxArticles, channel)
                    lastOk = report.ok
                    lastFailed = report.failed
                    lastMessages = report.messages
                    if (report.lastFetch > 0) lastFetchMillis = report.lastFetch
                } finally {
                    syncRunning = false
                }
            }
        }
        return JSONObject().put("started", true).toString()
    }

    @JavascriptInterface
    fun refreshStatus(): String {
        val messages = JSONArray()
        for (m in lastMessages) messages.put(m)
        return JSONObject().apply {
            put("running", syncRunning)
            put("ok", lastOk)
            put("failed", lastFailed)
            put("messages", messages)
            put("lastFetch", lastFetchMillis.toString())
        }.toString()
    }

    // ---- saved --------------------------------------------------------------

    @JavascriptInterface
    fun saved(): String {
        val arr = JSONArray()
        for (a in SavedStore.saved(ctx)) arr.put(a.toJson())
        return arr.toString()
    }

    @JavascriptInterface
    fun toggleSaved(json: String): String {
        return try {
            val o = JSONObject(json)
            val article = GdeltArticle.fromJson(o)
            if (article.url.isBlank()) {
                JSONObject().apply { put("ok", false); put("saved", false); put("error", "url is required") }.toString()
            } else {
                val nowSaved = SavedStore.toggle(ctx, article)
                JSONObject().apply { put("ok", true); put("saved", nowSaved); put("error", "") }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false); put("saved", false); put("error", e.message ?: e.javaClass.simpleName)
            }.toString()
        }
    }

    // ---- config ---------------------------------------------------------------

    @JavascriptInterface
    fun config(): String {
        val cfg = effectiveConfig()
        return JSONObject().apply {
            put("base", cfg.base)
            put("refreshSeconds", cfg.refreshSeconds)
            put("maxArticles", cfg.maxArticles)
        }.toString()
    }

    /**
     * `base`, if present, MUST be http:// or https:// — this is what
     * lets the deployment switch between the public HTTPS gateway
     * (https://api.diegonmarcos.com/news) and the WireGuard-mesh HTTP
     * host (http://news-gdelt:3019); see network_security_config.xml
     * for why only that one hostname is allowed to be cleartext.
     * Anything else (or a malformed scheme entirely) is rejected here
     * before it's ever handed to NewsApi/HttpURLConnection.
     */
    @JavascriptInterface
    fun setConfig(json: String): String {
        return try {
            val o = JSONObject(json)
            val base = if (o.has("base")) o.optString("base", "").trim() else null
            if (base != null && base.isNotEmpty() &&
                !base.startsWith("http://", ignoreCase = true) &&
                !base.startsWith("https://", ignoreCase = true)
            ) {
                return okErr(false, "base must be http:// or https://")
            }
            val refreshSeconds = if (o.has("refreshSeconds")) o.optInt("refreshSeconds", -1).takeIf { it > 0 } else null
            val maxArticles = if (o.has("maxArticles")) o.optInt("maxArticles", -1).takeIf { it > 0 } else null
            NewsConfigStore.setOverride(ctx, base?.takeIf { it.isNotEmpty() }, refreshSeconds, maxArticles)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- external links ---------------------------------------------------------

    /** Article URLs come from an upstream feed and are UNTRUSTED —
     *  only http/https may ever reach startActivity here, otherwise a
     *  malicious feed entry could hand us an arbitrary scheme
     *  (intent:, content:, file:, market:, ...) and pivot out of the
     *  browser sandbox this bridge is meant to be confined to. */
    @JavascriptInterface
    fun openExternal(url: String): String {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return okErr(false, "invalid url")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return okErr(false, "url must be http:// or https://")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- small JSON helpers -----------------------------------------------------

    private fun okErr(ok: Boolean, error: String): String =
        JSONObject().apply { put("ok", ok); put("error", error) }.toString()
}
