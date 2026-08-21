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

    private val bakedTopics by lazy { NewsTopicsConfig.parseTopics(topicsJson) }
    private val bakedApi    by lazy { NewsTopicsConfig.parseApi(topicsJson) }
    private val sources     by lazy { NewsSourcesConfig.parseSources(sourcesJson) }

    private fun api(): NewsApiConfig = NewsConfigStore.effective(ctx, bakedApi)

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

    fun articles(sourceId: String, topic: String): String = JSONArray().apply {
        NewsStore.articlesFor(ctx, sourceId, topic).forEach { put(articleJson(it)) }
    }.toString()

    fun timeline(sourceId: String, topic: String): String = JSONArray().apply {
        NewsStore.timelineFor(ctx, sourceId, topic).forEach { p ->
            put(JSONObject().put("date", p.date).put("value", p.value))
        }
    }.toString()

    /**
     * Blocking, networked. Returns the report; "is a sync running" is UI state
     * and stays with whoever drew the spinner.
     */
    fun sync(sourceId: String, activeChannel: String): String {
        val src = source(sourceId)
            ?: return JSONObject().put("error", "no source configured").toString()
        val cfg = api()
        val r = NewsSync.syncAll(
            ctx, src,
            NewsTopicsStore.effective(ctx, bakedTopics).filter { it.enabled },
            cfg.base, cfg.maxArticles, activeChannel,
        )
        return JSONObject()
            .put("ok", r.ok).put("failed", r.failed)
            .put("messages", JSONArray(r.messages))
            .put("lastFetch", r.lastFetch)
            .toString()
    }

    fun tone(sourceId: String, topic: String): String = JSONArray().apply {
        NewsStore.toneFor(ctx, sourceId, topic).forEach { e ->
            put(JSONObject().put("date", e.date).put("tone", e.tone))
        }
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
        SavedStore.saved(ctx).forEach { put(articleJson(it)) }
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

    private fun articleJson(a: GdeltArticle) = JSONObject()
        .put("url", a.url).put("title", a.title).put("seendate", a.seendate)
        .put("socialimage", a.socialimage).put("domain", a.domain)
        .put("language", a.language).put("sourcecountry", a.sourcecountry)
        .put("tone", a.tone ?: JSONObject.NULL)
}
