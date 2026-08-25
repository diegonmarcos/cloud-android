package com.diegonmarcos.cloudnews

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

    companion object {
        /** Reserved id for the synthetic merged "All" channel prepended
         *  to a QUERY-DRIVEN source's channel list (gdelt-self,
         *  google-news) — see [channelsForSource]/[articles]. Contains
         *  "::", the same separator [NewsStore]'s cacheKey kdoc already
         *  documents as unable to appear in a source id or a GDELT
         *  query-term topic "in practice". A user-typed custom topic
         *  (Topics tab addTopic) is free-form text and could in theory
         *  still collide, but a hand-typed search query containing this
         *  exact string is vanishingly unlikely, and no BAKED topic can
         *  ever equal it (data/topics.json is fixed at build time) — so
         *  this is a best-effort reservation, not a cryptographic
         *  guarantee, same tradeoff the codebase already accepts for
         *  "::" elsewhere. This id is NEVER a real topic: it must never
         *  reach [NewsSync] as a query term (only topic ids sourced
         *  from [NewsTopicsStore] do) and [topics] must never report it
         *  as one (it reads straight from NewsTopicsStore, never from
         *  [channelsForSource], so it structurally can't). */
        private const val ALL_CHANNEL_ID = "__all::channels__"
    }

    @Volatile private var syncRunning = false
    @Volatile private var lastOk = 0
    @Volatile private var lastFailed = 0
    @Volatile private var lastMessages: List<String> = emptyList()
    @Volatile private var lastFetchMillis = 0L

    // ── the engine ───────────────────────────────────────────────────────────
    // News data work lives in Cloud-Lib-News.apk now; this class is the front
    // end. Active source/channel moved with it (SourceStore/ChannelStore are
    // lib-stored), which is what lets these forwards keep the bridge's exact
    // signatures.
    private val client by lazy {
        DataBackendClient(ctx, "com.diegonmarcos.cloudlib.news",
                          "com.diegonmarcos.superapp.news.NewsBackendService")
    }

    private fun engine(method: String, vararg args: String): String {
        seedOnce()
        return client.call(method, *args)
    }

    /**
     * Hand over the one thing a re-fetch cannot rebuild: saved articles.
     *
     * Cached articles re-fetch on the next sync; SavedStore is the user's own
     * state and moved into the engine's private storage with it. Marked done
     * only when the engine confirms, so a failed seed is retried rather than
     * skipped forever.
     */
    @Volatile private var seeded = false
    @Synchronized
    private fun seedOnce() {
        if (seeded) return
        seeded = true
        val prefs = ctx.getSharedPreferences("news_bridge", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false)) return
        val legacy = ctx.getSharedPreferences("news_legacy_saved", Context.MODE_PRIVATE)
            .getString("saved", null)
        if (legacy.isNullOrBlank()) { prefs.edit().putBoolean("seeded", true).apply(); return }
        val ok = runCatching { JSONObject(client.call("seed", legacy)).optBoolean("ok") }
            .getOrDefault(false)
        if (ok) prefs.edit().putBoolean("seeded", true).apply()
    }

    // ---- baked config (data/topics.json -> BuildConfig.TOPICS_B64) ------





    // ---- sources (data/sources.json -> BuildConfig.SOURCES_B64) -----------





    // ---- media (data/media.json -> BuildConfig.MEDIA_B64) -----------------



    // ---- events (data/events.json -> BuildConfig.EVENTS_B64) --------------



    /** [bakedSources] always has at least one entry (its own fallback —
     *  see NewsSourcesConfig.parseSources), so `.first()` below never
     *  hits an empty list; it only fires if the persisted active id no
     *  longer exists in a rebuilt sources.json (a source was removed
     *  from the config after the user picked it). */

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

    // ---- channels -----------------------------------------------------------

    /** [source]'s channel list for the UI's second nav row — ALWAYS
     *  non-empty (falls back to a single channel built from [source]'s
     *  own id/label/url as an absolute last resort, so a brand-new or
     *  not-yet-discovered source is never left with nothing to pick).
     *  Three populating strategies, per [NewsChannelConfig]'s kdoc:
     *   - query-driven (gdelt-self, google-news): a synthetic
     *     [ALL_CHANNEL_ID] "All" channel FIRST (see that const's kdoc —
     *     this is the merged feed, [articles]' whole reason for
     *     existing), followed by one channel per entry in the LIVE
     *     topics store (baked + custom, in effective order) — NOT the
     *     baked topics.json list alone — so adding/removing a topic in
     *     the Topics tab is reflected immediately, with zero
     *     duplication of the topic list into data/sources.json. Each
     *     non-synthetic channel's id IS the topic id. Being first makes
     *     "All" the fallback in [activeChannelId] — i.e. the default —
     *     for any source that hasn't had a channel explicitly picked
     *     yet, matching the pre-two-row-nav default of landing on "All".
     *   - dynamic-channel (ntfy-self): the last successfully-discovered
     *     list from [DynamicChannelStore] (refreshed by NewsSync's
     *     syncRssDynamic on every [refresh]).
     *   - fixed rss (bbc-world, guardian-world, ...): [source]'s own
     *     declared `channels`, already guaranteed non-empty by
     *     [NewsSourcesConfig.parseSources].
     *  Deliberately NO synthetic "All" for the dynamic-channel/fixed
     *  cases: those channels are genuinely different feeds (BBC's World
     *  vs Business section, ntfy-self's independent topics), not facets
     *  of one query set — merging them isn't what the old "All" meant,
     *  and isn't asked for here. */

    /** The channel a refresh()/articles("") call should use: the
     *  per-source persisted choice (see [ChannelStore]), defaulting to
     *  [source]'s first channel — never blank, since
     *  [channelsForSource] always returns at least one entry. */

    /** `channels` is ALWAYS populated (never an empty array) — see
     *  [channelsForSource] — so the UI's second nav row always has
     *  something to render the moment a source is picked. */
    @JavascriptInterface
    fun sources(): String {
        return engine("sources")
    }

    @JavascriptInterface
    fun activeSource(): String = JSONObject().apply {
        return engine("activeSource")
    }.toString()

    @JavascriptInterface
    fun setSource(id: String): String {
        return engine("setSource", id)
    }

    /** The active source's active channel — see [activeChannelId]. */
    @JavascriptInterface
    fun activeChannel(): String =
        JSONObject().apply {
        return engine("activeChannel")
    }.toString()

    /** Persists [id] as the active source's active channel — see
     *  [ChannelStore]. Rejects an id that isn't one of the active
     *  source's current [channelsForSource] (typo, stale channel from
     *  before a rebuild/re-discovery, id belonging to a different
     *  source, ...) rather than silently accepting an unfetchable
     *  channel. */
    @JavascriptInterface
    fun setChannel(id: String): String {
        return engine("setChannel", id)
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
        return engine("topics")
    }

    /** `channel` (the former `topic` param — same bridge slot, new
     *  meaning: a CHANNEL id, see the class kdoc) "" means the active
     *  source's active channel (see [activeChannelId]); for a
     *  query-driven source channel==topic so this is exactly the same
     *  cache lookup it always was, just for one topic/channel — UNLESS
     *  that channel is the synthetic [ALL_CHANNEL_ID] "All" entry (see
     *  [channelsForSource]), in which case this is a MERGE across every
     *  currently-ENABLED topic of the active source: each topic's
     *  cached articles concatenated, deduped by url (the same story
     *  frequently comes back under two topics — [NewsStore.articlesFor]
     *  keeps the first/newest-inserted occurrence), then sorted. Never
     *  merges across SOURCES — only the active source's own topics, via
     *  [NewsStore]'s per-(source,topic) cache keys. Newest first
     *  (seendate is a zero-padded string for every source — RssParser
     *  reformats rss dates into the same shape GDELT uses — so a plain
     *  descending string sort is already chronological, see
     *  NewsStore.replaceArticles). limit falls back to the effective
     *  config's maxArticles if blank/unparseable, and is applied AFTER
     *  the merge+sort so it still reflects the newest N across every
     *  topic rather than the newest N of just the first topic. */
    @JavascriptInterface
    fun articles(channel: String, limit: String): String {
        return engine("articles", channel, limit)
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
        return engine("timeline", topic)
    }

    /** Same reasoning as [timeline], gated on "tone" instead. */
    @JavascriptInterface
    fun tone(topic: String): String {
        return engine("tone", topic)
    }

    /** enabled is "true"/"false" (bridge string convention, see
     *  [com.diegonmarcos.cloudcalendar.CalBridge.events] for precedent
     *  in the sibling app). */
    @JavascriptInterface
    fun setTopicEnabled(topic: String, enabled: String): String {
        return engine("setTopicEnabled", topic, enabled)
    }

    @JavascriptInterface
    fun addTopic(topic: String, label: String): String {
        return engine("addTopic", topic, label)
    }

    /** Removing a topic drops its cache under EVERY source (not just
     *  the active one) — a topic disabled/removed here is meant to
     *  stop being tracked everywhere, and each source has its own
     *  namespaced cache entry for it (see NewsStore's cacheKey kdoc). */
    @JavascriptInterface
    fun removeTopic(topic: String): String {
        return engine("removeTopic", topic)
    }

    // ---- refresh ------------------------------------------------------------

    /**
     * Refreshes THREE things per call, deliberately bounded differently
     * for each — there is no "active view" signal from the UI (no such
     * method is part of the bridge contract), so this always covers all
     * three rather than gating on a tab the native side has no way to
     * know about:
     *  - NEWS: unchanged from before Media/Events existed — the active
     *    source's active channel/topics only (see [NewsSync]'s own
     *    scoping kdoc).
     *  - MEDIA: exactly ONE of the 8 YouTube channels, chosen by plain
     *    round-robin ([MediaRotationStore]) — never all 8. `mediaItems`
     *    takes an explicit channel id per call and is otherwise
     *    stateless, so unlike a fixed rss source there is no persisted
     *    "active channel" selection to fetch instead; round-robin still
     *    guarantees every channel is refreshed at least once every 8
     *    calls rather than picking one arbitrarily forever.
     *  - EVENTS: every ENABLED calendar is offered to [EventsSync], but
     *    it only actually fetches a calendar whose own `refresh_hours`
     *    window (data/events.json) has elapsed since its last sync —
     *    same throttle ea_cloud-calendar's CalSync already uses. In
     *    steady state (refresh() called every `refreshSeconds`, default
     *    60s) that means zero-to-a-few of the ~20 calendars are actually
     *    fetched per call, not 20 — the only call that touches all of
     *    them is the very first one after install, when every enabled
     *    calendar is legitimately due (cold cache).
     * All three failures are isolated from each other (same as within
     * each engine): a failing events sync never blocks/discards a
     * successful news or media sync in the same call, and vice versa.
     */
    @JavascriptInterface
    fun refresh(): String {
        // The running flag and last-result fields stay HERE: refreshStatus is
        // what the spinner polls, and the engine deliberately returns a report
        // rather than tracking state. Field names are the bridge's existing
        // ones - refreshStatus already reads them.
        if (syncRunning) return JSONObject().put("started", false).toString()
        syncRunning = true
        executor.execute {
            try {
                val r = JSONObject(engine("sync", ""))
                lastOk = r.optInt("ok"); lastFailed = r.optInt("failed")
                lastFetchMillis = r.optLong("lastFetch")
                val msgs = r.optJSONArray("messages")
                lastMessages = (0 until (msgs?.length() ?: 0)).map { msgs!!.optString(it) }
            } finally { syncRunning = false }
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

    // ---- media (YouTube channels) --------------------------------------------

    /** All 8 configured YouTube channels — ALWAYS from data/media.json
     *  (there is no per-user enable/disable overlay for media, unlike
     *  news topics). `thumbnail` is the newest cached video's thumbnail
     *  for that channel (offline-first: read straight from [NewsStore],
     *  no network here), or "" before that channel has ever synced once. */
    @JavascriptInterface
    fun media(): String {
        return engine("mediaChannels")
    }

    /** `channel` "" merges every configured channel's cached videos,
     *  newest first, deduped by url (same merge semantics as
     *  [articles]'s synthetic "All" channel) — otherwise just that one
     *  channel's cache. An unknown [channel] id degrades to an empty
     *  result rather than an error, same "never throw on user input"
     *  spirit as the rest of this bridge. `published` is epoch millis
     *  (bridge convention: every millis value crosses as a STRING),
     *  reparsed from [GdeltArticle.seendate]'s GDELT-format string via
     *  [seendateToMillis] since that's the only form the shared article
     *  cache stores a timestamp in. */
    @JavascriptInterface
    fun mediaItems(channel: String, limit: String): String {
        return engine("mediaItems", channel, limit)
    }

    // ---- events (ICS calendar feeds) ------------------------------------------

    /** Merged, sorted view across every ENABLED data/events.json
     *  calendar whose cached events overlap `[fromUtcMillis,
     *  toUtcMillis)` — pure cache read, no network (offline-first, same
     *  as every other read in this bridge); [refresh] is what keeps
     *  [EventsStore] warm. `calendarLabel`/`color` are joined in from
     *  the baked calendar config at read time (not stored per-event —
     *  see [CalEvent]'s kdoc in EventsModels.kt). */
    @JavascriptInterface
    fun events(fromUtcMillis: String, toUtcMillis: String): String {
        return engine("events", fromUtcMillis, toUtcMillis)
    }

    /** Toggles a saved event by id — same add-if-absent/remove-if-
     *  present contract as [toggleSaved]. [json] is expected to carry
     *  the same shape [events]/[savedEvents] hand back to the UI
     *  (id/calendarId/calendarLabel/color/title/location/start/end/
     *  allDay); a full [SavedEvent] snapshot is stored so a saved event
     *  still renders after it ages out of [EventsStore]'s feed cache or
     *  its calendar disappears from a future data/events.json rebuild —
     *  see [SavedEvent]'s kdoc. */
    @JavascriptInterface
    fun saveEvent(json: String): String {
        return engine("saveEvent", json)
    }

    /** Same response shape as [events] — a saved event renders through
     *  the exact same UI card either way. */
    @JavascriptInterface
    fun savedEvents(): String {
        return engine("savedEvents")
    }

    @JavascriptInterface
    fun isEventSaved(id: String): String =
        JSONObject().apply {
        return engine("isEventSaved", id)
    }.toString()


    private val GDELT_MILLIS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Reparses [RssParser]'s GDELT-format `seendate` string
     *  (`yyyyMMdd'T'HHmmss'Z'`, always UTC — see that class's kdoc) into
     *  epoch millis for [mediaItems]' `published` field. 0 (never
     *  throws) for a blank/unparseable seendate (an article whose date
     *  never parsed at all — see RssParser.parseDate). */

    // ---- saved --------------------------------------------------------------

    @JavascriptInterface
    fun saved(): String {
        return engine("saved")
    }

    @JavascriptInterface
    fun toggleSaved(json: String): String {
        return engine("toggleSaved", json)
    }

    // ---- config ---------------------------------------------------------------

    @JavascriptInterface
    fun config(): String {
        return engine("config")
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
        // Validation moved WITH the setter: the engine rejects a base that is
        // not http(s), so the rule lives next to the store it protects.
        return engine("setConfig", json)
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
