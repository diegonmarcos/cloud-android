package com.diegonmarcos.superapp.news

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The news BACK END: topics, articles, timeline/tone series, saved items,
 * event calendars and media channels — all as JSON.
 *
 * Everything here is data work that used to sit in the app's NewsBridge. What
 * stays in the app is the part that needs an Activity or the app's own
 * identity: opening a link, the About page's build fields, the WebView itself.
 *
 * Every method blocks and does real network I/O on the sync paths, exactly
 * like the underlying engines. Callers run it off the main thread.
 */
class NewsEngine(context: Context) {

    private val ctx = context.applicationContext

    private fun decode(b64: String): String =
        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)

    private val topicsJson  by lazy { decode(BuildConfig.TOPICS_B64) }
    private val sourcesJson by lazy { decode(BuildConfig.SOURCES_B64) }
    private val mediaJson   by lazy { decode(BuildConfig.MEDIA_B64) }
    private val eventsJson  by lazy { decode(BuildConfig.EVENTS_B64) }

    /** Must match the bridge's sentinel EXACTLY - it is a magic channel id,
     *  not a readable name, and "all" would simply never match. */
    private val ALL_CHANNEL_ID = "__all::channels__"

    private val bakedTopics by lazy { NewsTopicsConfig.parseTopics(topicsJson) }
    private val bakedApi    by lazy { NewsTopicsConfig.parseApi(topicsJson) }
    private val sources     by lazy { NewsSourcesConfig.parseSources(sourcesJson) }

    private fun api(): NewsApiConfig = NewsConfigStore.effective(ctx, bakedApi)

    /** The active source, resolved HERE: SourceStore is lib-stored, so after
     *  cutover this prefs entry lives in the engine. That is what lets these
     *  signatures match the bridge's - the caller does not pass a source. */
    private fun activeSource(): NewsSourceConfig {
        val id = SourceStore.active(ctx, NewsSourcesConfig.parseDefaultSource(sourcesJson))
        return sources.firstOrNull { it.id == id } ?: sources.first()
    }

    /** NewsConfigStore is lib-stored, so the effective config lives here too. */
    fun config(): String {
        val c = api()
        return JSONObject()
            .put("base", c.base)
            .put("refreshSeconds", c.refreshSeconds)
            .put("maxArticles", c.maxArticles)
            .toString()
    }

    fun setConfig(json: String): String {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad payload").toString()
        val base = o.optString("base", "").trim()
        // Same guard the bridge enforced - a base that is not http(s) would
        // make every later fetch fail with nothing pointing at the cause.
        if (base.isNotEmpty() && !base.startsWith("http://", true) && !base.startsWith("https://", true))
            return JSONObject().put("ok", false).put("error", "base must be http:// or https://").toString()
        NewsConfigStore.setOverride(
            ctx,
            base.takeIf { it.isNotEmpty() },
            if (o.has("refreshSeconds")) o.optInt("refreshSeconds") else null,
            if (o.has("maxArticles")) o.optInt("maxArticles") else null,
        )
        return JSONObject().put("ok", true).toString()
    }

    fun activeSourceId(): String =
        JSONObject().put("id", activeSource().id).toString()

    fun setSource(id: String): String {
        if (id.isBlank()) return JSONObject().put("ok", false).put("error", "id is required").toString()
        SourceStore.setActive(ctx, id.trim())
        return JSONObject().put("ok", true).toString()
    }

    fun activeChannelId(): String {
        val src = activeSource()
        return JSONObject().put("id", ChannelStore.active(ctx, src.id, ALL_CHANNEL_ID)).toString()
    }

    fun setChannel(id: String): String {
        ChannelStore.setActive(ctx, activeSource().id, id.trim())
        return JSONObject().put("ok", true).toString()
    }

    private fun source(id: String): NewsSourceConfig? =
        sources.firstOrNull { it.id == id }
            ?: sources.firstOrNull { it.id == NewsSourcesConfig.parseDefaultSource(sourcesJson) }
            ?: sources.firstOrNull()

    // ── topics ───────────────────────────────────────────────────────────────

    fun topics(): String = JSONArray().apply {
        NewsTopicsStore.effective(ctx, bakedTopics).forEach { t ->
            put(JSONObject()
                .put("topic", t.topic).put("label", t.label)
                .put("enabled", t.enabled).put("custom", t.custom))
        }
    }.toString()

    // ── articles ─────────────────────────────────────────────────────────────

    /**
     * Matches the bridge's contract exactly: (channel, limit), with the active
     * source resolved here.
     *
     * The query-driven + "all channels" case fans out across every ENABLED
     * topic and merges - reading one topic instead would silently return a
     * fraction of the feed, which is what an earlier signature of this method
     * would have done.
     */
    fun articles(channel: String, limit: Int): String {
        val src = activeSource()
        val max = limit.takeIf { it > 0 } ?: api().maxArticles
        val chanId = channel.ifBlank { ChannelStore.active(ctx, src.id, ALL_CHANNEL_ID) }
        val list = if (src.queryDriven && chanId == ALL_CHANNEL_ID) {
            NewsStore.articlesFor(ctx, src.id,
                NewsTopicsStore.effective(ctx, bakedTopics).filter { it.enabled }.map { it.topic })
        } else {
            NewsStore.articlesFor(ctx, src.id, chanId)
        }
        return JSONArray().apply {
            list.sortedByDescending { it.seendate }.take(max).forEach { put(it.toJson()) }
        }.toString()
    }


    fun timeline(topic: String): String = JSONArray().apply {
        NewsStore.timelineFor(ctx, activeSource().id, topic).forEach { put(it.toJson()) }
    }.toString()

    /**
     * Blocking, networked. Returns the report; "is a sync running" is UI state
     * and stays with whoever drew the spinner.
     */
    fun sync(activeChannel: String): String {
        val src = activeSource()
        // Blank means "whatever is active" - resolved here so the caller does
        // not need a second round trip just to name the channel it is on.
        val channel = activeChannel.ifBlank { ChannelStore.active(ctx, src.id, ALL_CHANNEL_ID) }
        val cfg = api()
        val r = NewsSync.syncAll(
            ctx, src,
            NewsTopicsStore.effective(ctx, bakedTopics).filter { it.enabled },
            cfg.base, cfg.maxArticles, channel,
        )
        return JSONObject()
            .put("ok", r.ok).put("failed", r.failed)
            .put("messages", JSONArray(r.messages))
            .put("lastFetch", r.lastFetch)
            .toString()
    }

    // ToneEntry is url/title/tone/domain — NOT date/value like TimelinePoint.
    // Use the model's own serializer, which is what the bridge emitted; my
    // hand-rolled keys guessed a `date` field that does not exist.
    fun tone(topic: String): String = JSONArray().apply {
        NewsStore.toneFor(ctx, activeSource().id, topic).forEach { put(it.toJson()) }
    }.toString()

    fun sources(): String = JSONArray().apply {
        sources.forEach { src ->
            put(JSONObject().put("id", src.id).put("label", src.label))
        }
    }.toString()

    // ── topic mutations ──────────────────────────────────────────────────────
    // Baked defaults are needed to distinguish a custom topic from a disabled
    // built-in, so they are passed from here rather than re-read by the store.

    fun setTopicEnabled(topic: String, enabled: Boolean): String {
        NewsTopicsStore.setEnabled(ctx, topic, enabled)
        return """{"ok":true}"""
    }

    fun addTopic(topic: String, label: String): String =
        JSONObject().put("topic", NewsTopicsStore.addTopic(ctx, topic, label, bakedTopics)).toString()

    fun removeTopic(topic: String): String =
        JSONObject().put("topic", NewsTopicsStore.removeTopic(ctx, topic, bakedTopics)).toString()

    // ── saved events ─────────────────────────────────────────────────────────

    fun savedEvents(): String = JSONArray().apply {
        SavedEventsStore.saved(ctx).forEach { put(it.toJson()) }
    }.toString()

    fun isEventSaved(id: String): String =
        JSONObject().put("saved", SavedEventsStore.isSaved(ctx, id)).toString()

    /** Returns the NEW state, like toggleSaved - the caller re-renders from
     *  truth rather than assuming its optimistic guess landed. */
    fun saveEvent(eventJson: String): String {
        val e = runCatching { SavedEvent.fromJson(JSONObject(eventJson)) }.getOrNull()
            ?: return JSONObject().put("error", "bad payload").toString()
        return JSONObject().put("saved", SavedEventsStore.toggle(ctx, e)).toString()
    }

    // ── saved ────────────────────────────────────────────────────────────────

    fun saved(): String = JSONArray().apply {
        SavedStore.saved(ctx).forEach { put(it.toJson()) }
    }.toString()

    /** Returns the NEW saved state, so the caller re-renders from the truth
     *  rather than assuming its optimistic guess landed. */
    fun toggleSaved(articleJson: String): String {
        val o = JSONObject(articleJson)
        val a = GdeltArticle(
            url = o.optString("url"),
            title = o.optString("title"),
            seendate = o.optString("seendate"),
            socialimage = o.optString("socialimage"),
            domain = o.optString("domain"),
            language = o.optString("language"),
            sourcecountry = o.optString("sourcecountry"),
            tone = if (o.has("tone") && !o.isNull("tone")) o.optDouble("tone") else null,
        )
        return JSONObject().put("saved", SavedStore.toggle(ctx, a)).toString()
    }

    /**
     * One-time handoff of the articles a re-fetch CANNOT rebuild.
     *
     * NewsStore lives in PRIVATE app storage and is a cache - the next sync
     * repopulates it. SavedStore is not: an article the user saved is their
     * own state, and once the engine's data directory replaces the app's it
     * exists nowhere the app can see. Only that slice is seeded.
     */
    fun seed(savedJson: String): String {
        val arr = runCatching { JSONArray(savedJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad payload").toString()
        var taken = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            val a = GdeltArticle(
                url = url,
                title = o.optString("title"),
                seendate = o.optString("seendate"),
                socialimage = o.optString("socialimage"),
                domain = o.optString("domain"),
                language = o.optString("language"),
                sourcecountry = o.optString("sourcecountry"),
                tone = if (o.has("tone") && !o.isNull("tone")) o.optDouble("tone") else null,
            )
            // toggle() flips state, so only call it for something not already
            // saved here - seeding twice would UNSAVE everything.
            if (!SavedStore.isSaved(ctx, url)) { SavedStore.toggle(ctx, a); taken++ }
        }
        return JSONObject().put("ok", true).put("taken", taken).toString()
    }

    /** Whether this engine already holds saved articles. */
    fun hasData(): String =
        JSONObject().put("hasData", SavedStore.saved(ctx).isNotEmpty()).toString()

    // ── events ───────────────────────────────────────────────────────────────

    fun events(fromUtc: Long, toUtc: Long): String {
        // events.json, not topics.json - separate config file, separate bake.
        val cals = EventCalendars.parse(eventsJson)
        val ids = cals.filter { it.enabled }.map { it.id }
        return JSONArray().apply {
            EventsStore.eventsFor(ctx, ids, fromUtc, toUtc).forEach { e ->
                put(JSONObject()
                    .put("id", e.id).put("calendarId", e.calendarId)
                    .put("title", e.title).put("location", e.location)
                    .put("start", e.startUtcMillis).put("end", e.endUtcMillis)
                    .put("allDay", e.allDay))
            }
        }.toString()
    }

    // ── media ────────────────────────────────────────────────────────────────

    fun mediaChannels(): String = JSONArray().apply {
        MediaConfig.parseChannels(mediaJson).forEach { c ->
            put(JSONObject().put("id", c.id).put("label", c.label).put("channelId", c.channelId))
        }
    }.toString()

    /**
     * The media grid: cached items across every configured channel (or one),
     * newest first, de-duplicated by URL.
     *
     * Pure cache read - no network. `sync` is what keeps NewsStore warm, and
     * that separation is why this stays fast offline.
     */
    fun mediaItems(channel: String, limit: Int): String {
        val channels = MediaConfig.parseChannels(mediaJson)
        val max = limit.takeIf { it > 0 } ?: api().maxArticles
        val targets = if (channel.isBlank()) channels else channels.filter { it.id == channel }
        // LinkedHashMap + putIfAbsent: the same video can appear under more
        // than one channel; first wins, and insertion order is kept stable
        // before the sort.
        val merged = LinkedHashMap<String, Pair<GdeltArticle, String>>()
        targets.forEach { c ->
            NewsStore.articlesFor(ctx, MediaConfig.SOURCE_ID, c.id).forEach { a ->
                if (a.url.isNotBlank()) merged.putIfAbsent(a.url, a to c.label)
            }
        }
        return JSONArray().apply {
            merged.values.sortedByDescending { it.first.seendate }.take(max)
                .forEach { (a, channelLabel) ->
                    put(JSONObject()
                        .put("videoId", a.videoId ?: "")
                        .put("title", a.title)
                        .put("url", a.url)
                        .put("thumbnail", a.thumbnail ?: "")
                        .put("published", seendateToMillis(a.seendate).toString())
                        .put("channelLabel", channelLabel))
                }
        }.toString()
    }

    /** GDELT stamps look like 20260821T143000Z. 0 for anything unparseable -
     *  a bad timestamp must not drop the item from the grid. */
    private fun seendateToMillis(seendate: String): Long {
        if (seendate.isBlank()) return 0L
        return runCatching {
            java.time.LocalDateTime.parse(seendate, GDELT_FMT)
                .atOffset(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private val GDELT_FMT: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    // No hand-rolled article serializer: GdeltArticle.toJson() also emits
    // videoId and thumbnail, which mine dropped - saved YouTube items would
    // have rendered without their thumbnails, with nothing reporting why.
}
