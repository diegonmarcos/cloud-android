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

/** One GDELT article, as returned by /articles. Mirrors TS `GdeltArticle`.
 *  Also the shared article shape for RSS-sourced articles (see
 *  RssParser.kt) — there is deliberately no separate "RssArticle" type,
 *  the bridge/UI contract is one article shape regardless of source. */
data class GdeltArticle(
    val url: String,
    val title: String,
    val seendate: String,
    val socialimage: String,
    val domain: String,
    val language: String,
    val sourcecountry: String,
    /** Sentiment score from GDELT's tone analysis, roughly -100..+100.
     *  NULLABLE ON PURPOSE: null means "this source never measured a
     *  tone for this article", which is a different fact than "the
     *  tone measured 0.0 (neutral)". Every rss source in
     *  data/sources.json was verified by hand to carry no tone/
     *  sentiment field at all (see that file's kdoc) — RssParser.kt
     *  therefore always produces tone = null, never 0.0, which would
     *  otherwise render in the UI as a false "measured as neutral".
     *  Only GdeltArticle.fromJson (GDELT's own /articles response) or
     *  NewsApi's real GDELT fetch ever sets a non-null value. */
    val tone: Double?,
    /** YouTube Atom-only: the `<yt:videoId>` of the video this "article"
     *  actually is. NULLABLE, added at the end with a default so every
     *  existing named-arg construction of this class (GDELT responses,
     *  every non-YouTube rss feed) is unaffected — see RssParser.kt for
     *  the only place this is ever populated, and MediaModels.kt for
     *  why a YouTube video is modelled as a [GdeltArticle] at all
     *  (reusing the one shared article shape/cache instead of a second
     *  "VideoItem" type and a parallel cache). null for every
     *  non-YouTube article, always. */
    val videoId: String? = null,
    /** YouTube Atom-only: the `<media:thumbnail url="...">` for
     *  [videoId]. Same nullable/default-at-the-end reasoning as
     *  [videoId] — never populated for a non-YouTube article. */
    val thumbnail: String? = null,
    /** The FEED-LEVEL (not per-article) publisher icon: RSS's
     *  `<channel><image><url>` (RSS 1.0/RDF's document-level
     *  `<image rdf:about="..."><url>` / self-closing `<image
     *  rdf:resource="...">` counts as the same thing — see RssParser.kt)
     *  or Atom's `<icon>`/`<logo>`, parsed ONCE per feed and copied onto
     *  every [GdeltArticle] that feed produces. NULLABLE ON PURPOSE, and
     *  genuinely null (never a fabricated favicon URL) for any feed that
     *  simply doesn't carry one — see RssParser.kt's class kdoc for why
     *  this app will never hit a publisher's `/favicon.ico` itself. This
     *  is the "tweets" view's per-item SOURCE avatar: attaching it to
     *  every article (rather than only living on [NewsChannelConfig])
     *  is what lets a per-item avatar render with zero extra plumbing
     *  through NewsSync/NewsStore, which only ever move [GdeltArticle]
     *  lists around. Independent of [socialimage] (that article's own
     *  photo) and [thumbnail] (a YouTube video's own thumbnail) —
     *  all three can be non-empty/non-null on the same YouTube
     *  "article" simultaneously, though in practice no sampled YouTube
     *  Atom feed carries an `<icon>`/`<logo>` at all. */
    val sourceImage: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("seendate", seendate)
        put("socialimage", socialimage)
        put("domain", domain)
        put("language", language)
        put("sourcecountry", sourcecountry)
        // Explicit JSON null (JSONObject.NULL), never omitted and never
        // 0.0 — see the tone kdoc above. NewsBridge crosses this
        // straight through to JS, where `null` is exactly what a
        // toneless article should read as.
        put("tone", tone ?: JSONObject.NULL)
        put("videoId", videoId ?: JSONObject.NULL)
        put("thumbnail", thumbnail ?: JSONObject.NULL)
        put("sourceImage", sourceImage ?: JSONObject.NULL)
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
            // Missing key, explicit JSON null, or a value that doesn't
            // parse as a number all degrade to null — never to 0.0,
            // same reasoning as the field's kdoc above.
            tone = if (o.has("tone") && !o.isNull("tone")) {
                o.optDouble("tone").takeUnless { it.isNaN() }
            } else null,
            videoId     = if (o.has("videoId") && !o.isNull("videoId")) o.optString("videoId").takeIf { it.isNotBlank() } else null,
            thumbnail   = if (o.has("thumbnail") && !o.isNull("thumbnail")) o.optString("thumbnail").takeIf { it.isNotBlank() } else null,
            sourceImage = if (o.has("sourceImage") && !o.isNull("sourceImage")) o.optString("sourceImage").takeIf { it.isNotBlank() } else null,
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

/**
 * One sub-channel within a data/sources.json source — the unit the UI's
 * second nav row picks from (row 1 picks the source, row 2 picks the
 * channel). Three ways a source's channel list is populated, uniformly
 * exposed as `List<NewsChannelConfig>` regardless of which:
 *  - a FIXED rss source (bbc-world, guardian-world, dw-en, hackernews,
 *    ...) declares its channels literally in data/sources.json's
 *    `channels` array — [url] is that declared sub-feed's URL.
 *  - a QUERY-DRIVEN source (gdelt-self, google-news) carries NO
 *    `channels` in sources.json at all; [NewsBridge] synthesizes one
 *    channel per entry in the user's live topics store (baked +
 *    custom, in effective order), each channel's [id] equal to that
 *    topic's id — see NewsBridge.channelsForSource. [url] is unused
 *    (blank) for a synthesized channel: the query template lives on
 *    the parent [NewsSourceConfig.url] instead.
 *  - a DYNAMIC-CHANNEL source (ntfy-self) also carries no `channels`
 *    in sources.json — its channels are discovered at runtime from
 *    [NewsSourceConfig.channelsUrl] (see [DynamicChannels]), cached on
 *    disk (NewsStore's DynamicChannelStore), and re-discovered on
 *    every sync. [url] here IS meaningful: it's that discovered
 *    channel's own feed URL.
 *
 * [group] is optional and ONLY ever set by [DynamicChannels] parsing a
 * discovery payload that itself carried a group/category field — a
 * declared or synthesized channel never sets it. Lets a UI section a
 * large dynamic channel list (e.g. ntfy-self's ~142 channels) without
 * this app inventing groups the source data never provided. For the
 * real rss-gateway `channels.json` shape (see [DynamicChannels]) it is
 * DERIVED from [path] (every segment but the leaf), not read as its
 * own field — the payload has no separate group/category field at all.
 *
 * [path] is the discovery payload's full slash-separated hierarchy
 * (e.g. `"Cloud-Infra/CICD/GH"`) verbatim, ONLY ever set by
 * [DynamicChannels] from a real `path` field — kept alongside the
 * derived [group] because it is strictly more information (the whole
 * chain, not just the parent) and costs nothing to carry through.
 */
data class NewsChannelConfig(
    val id: String,
    val label: String,
    val url: String = "",
    val group: String? = null,
    val path: String? = null,
)

/**
 * One entry from data/sources.json's `sources` array — a place the app
 * can pull articles from: the self-hosted GDELT proxy (`kind ==
 * "gdelt"`, talks to [NewsApiConfig.base]) or a public/self-hosted
 * RSS/Atom feed fetched directly from the handset (`kind == "rss"`,
 * see RssParser.kt). Mirrors [NewsTopicConfig]'s role for topics.json.
 *
 * `url` is only meaningful for a QUERY-DRIVEN rss source and may
 * contain a literal `{query}` placeholder that [queryDriven] sources
 * substitute a topic into (URL-encoded) — see NewsSync. A
 * non-query-driven rss source's articles come from its ACTIVE
 * [NewsChannelConfig.url] instead (declared in [channels], or
 * discovered — see [dynamicChannels]/[channelsUrl]/[base]), never from
 * this top-level `url`; that channel fetch is filed under the cache
 * key `(id, channel.id)` — see NewsStore's cacheKey.
 *
 * [channels] is the declared sub-feed list for a FIXED rss source —
 * always non-empty by the time [NewsSourcesConfig.parseSources] hands
 * it out (a fixed rss source with an empty/missing `channels` array in
 * the JSON falls back to a single synthetic channel built from this
 * source's own id/label/url, same "never leave the app with nothing
 * usable" reasoning as [NewsSourcesConfig]'s own FALLBACK_ID). It is
 * intentionally left EMPTY here (not synthesized at this layer) for a
 * query-driven source (channels come from the topics store instead,
 * which needs a Context this pure parsing layer doesn't have — see
 * NewsBridge.channelsForSource) and for a [dynamicChannels] source
 * (channels come from a network fetch, also Context/IO-dependent).
 *
 * `capabilities` is the UI's ONLY way to know whether timeline/tone
 * charts make sense for this source: no rss source in data/sources.json
 * carries a tone/sentiment field (verified by hand — see that file's
 * `_doc`), so every rss entry's capabilities list omits "timeline" and
 * "tone" entirely rather than the app returning empty chart data that
 * could be mistaken for "no signal this period".
 */
data class NewsSourceConfig(
    val id: String,
    val label: String,
    val kind: String, // "gdelt" | "rss"
    val url: String,
    val queryDriven: Boolean,
    val capabilities: List<String>,
    val requiresMesh: Boolean,
    val channels: List<NewsChannelConfig> = emptyList(),
    /** true only for ntfy-self: this source's channels are discovered
     *  at runtime from [channelsUrl] rather than declared in
     *  data/sources.json — see [DynamicChannels] and NewsSync's
     *  syncRssDynamic. */
    val dynamicChannels: Boolean = false,
    /** dynamic_channels only: prefixed onto a discovered channel's id
     *  (`"$base/$id"`) when that channel's discovery entry carries no
     *  explicit url/feed/href of its own. */
    val base: String = "",
    /** dynamic_channels only: the JSON endpoint listing this source's
     *  channels (ntfy-self: rss-gateway's `/feed/channels.json`). */
    val channelsUrl: String = "",
) {
    val isGdelt: Boolean get() = kind == "gdelt"
    val isRss: Boolean get() = kind == "rss"
    val hasTone: Boolean get() = "tone" in capabilities
    val hasTimeline: Boolean get() = "timeline" in capabilities
}

/**
 * Parses a data/sources.json `dynamic_channels: true` source's runtime
 * discovery payload (ntfy-self's rss-gateway `/feed/channels.json`)
 * into [NewsChannelConfig]s.
 *
 * SHAPE IS NOW CONFIRMED — ea_cloud-mail's SUPER RSS READER
 * (patches/0054-comms-RSS-channel-tree-.../CommsAccounts.java +
 * WorkerFeedSync.java) is a working consumer of this exact endpoint
 * already shipping in production, and
 * z_archive/ea_cloud-comms/test/test-rss-autoprovision.sh's live check
 * confirms the top-level shape against the real host. The document is
 * an OBJECT:
 * ```
 * { "base": "https://rss.diegonmarcos.com/feed",
 *   "counts": { "total": 142, ... },
 *   "channels": [ { "id": "...", "title": "...",
 *                   "path": "Cloud-Infra/CICD/GH",
 *                   "feed": "/feed/c/gh.atom" }, ... ] }
 * ```
 * — `title` (not `label`/`name`) is the display name, `path` is the
 * full slash-separated folder hierarchy (leaf included; see
 * [NewsChannelConfig.path]/[groupFromPath]), `feed` is the per-topic
 * ATOM url and may be ORIGIN-RELATIVE (resolved against the payload's
 * own `base` — falling back to [NewsSourceConfig.base], the configured
 * default, only when the payload omits `base` entirely — never assumed
 * absolute). `counts.total` is a server-reported channel count,
 * independent of `channels.size` after this parses it — see
 * [DynamicChannels.Result.totalCount] for why that's surfaced instead
 * of silently trusted.
 *
 * AUTH: the commissioning patches carry an optional Bearer-token
 * concept (`comms_cloud_token`) for OTHER deployments of this same
 * rss-gateway software, but data/sources.json's own `_doc_source` for
 * ntfy-self records `feed_auth=none` for THIS deployment — so no
 * Authorization header is sent here. If a future deployment of
 * ntfy-self ever requires one, it would need a config slot threaded
 * through NewsSourceConfig/NewsApi.channels, not silently added here.
 *
 * The old field-name guesses (`label`/`name`, `url`/`href`,
 * `group`/`category`/`type`, a bare top-level array, a `feeds` key) are
 * kept as a SECONDARY fallback path — never the primary — for
 * resilience against a future/alternate deployment that doesn't match
 * this exact shape; they are try-if-`channels`-is-absent, not
 * try-first. NEVER throws or fabricates a placeholder channel: a
 * totally unparseable/unreachable body (caller passes in "" on a
 * failed fetch, or garbage) degrades to an empty list, and one
 * malformed entry within an otherwise-good array is skipped while the
 * rest of the document survives — same "one bad record doesn't take
 * down the whole document" contract as RssParser/IcsParser.
 */
object DynamicChannels {
    /** [parse]'s full result: the channels plus [totalCount] — the
     *  payload's `counts.total` field when present (null for a shape
     *  that doesn't carry one, e.g. the legacy fallback array). Letting
     *  the caller ([NewsSync]) compare `channels.size` against this
     *  turns a silent partial parse (some entries skipped as malformed,
     *  or the array truncated upstream) into a diagnosable sync-report
     *  message instead of a channel list that's just quietly short. */
    data class Result(val channels: List<NewsChannelConfig>, val totalCount: Int? = null)

    /** [base] is [NewsSourceConfig.base] — the CONFIGURED default,
     *  used only when the payload's own `base` field (preferred — see
     *  class kdoc) is missing, and (same as before) only ever consulted
     *  for a relative feed url or an entry that names an id but no feed
     *  url at all. */
    fun parse(json: String, base: String): List<NewsChannelConfig> = parseResult(json, base).channels

    fun parseResult(json: String, base: String): Result {
        if (json.isBlank()) return Result(emptyList())
        return runCatching {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                // Legacy/defensive shape only: a bare top-level array
                // carries no `base`/`counts`, so the configured [base]
                // is used as-is and totalCount is unknown.
                val arr = JSONArray(trimmed)
                return@runCatching Result(
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromEntry(it, base) } },
                )
            }

            val root = JSONObject(trimmed)
            val effectiveBase = root.optString("base", "").trim().ifEmpty { base }
            val totalCount = root.optJSONObject("counts")
                ?.takeIf { it.has("total") }
                ?.optInt("total")

            val primary = root.optJSONArray("channels")
            val arr = primary ?: extractArrayFallback(root)
                ?: return@runCatching Result(emptyList(), totalCount)
            Result(
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromEntry(it, effectiveBase) } },
                totalCount,
            )
        }.getOrDefault(Result(emptyList()))
    }

    /** SECONDARY path only, tried when the real `channels` key is
     *  absent — see class kdoc. Accepts an object wrapping the array
     *  under a `feeds` key, or — defensively — the first key anywhere
     *  in the object whose value is a non-empty JSON array of objects. */
    private fun extractArrayFallback(root: JSONObject): JSONArray? {
        root.optJSONArray("feeds")?.let { if (it.length() > 0) return it }
        val keys = root.keys()
        while (keys.hasNext()) {
            val candidate = root.optJSONArray(keys.next()) ?: continue
            if (candidate.length() > 0 && candidate.optJSONObject(0) != null) return candidate
        }
        return null
    }

    /** null (entry skipped) only when NONE of the id-ish keys are
     *  present — every other field degrades to a sane fallback instead
     *  of dropping the whole entry. `title` leads the label fallback
     *  chain (the real field), `feed` leads the url chain (the real
     *  field, resolved against [base] if relative — see [resolveUrl]),
     *  and `group` is DERIVED from a real `path` field when present
     *  (see [groupFromPath]), falling back to the old
     *  group/category/type guesses only when there's no `path` at all. */
    private fun fromEntry(o: JSONObject, base: String): NewsChannelConfig? {
        val id = firstNonBlank(o, "id", "name", "topic", "slug") ?: return null
        val label = firstNonBlank(o, "title", "label", "name") ?: id
        val rawUrl = firstNonBlank(o, "feed", "url", "href")
        val url = if (rawUrl != null) resolveUrl(rawUrl, base) else "${base.trimEnd('/')}/$id"
        val path = o.optString("path", "").trim().takeIf { it.isNotEmpty() }
        val group = path?.let { groupFromPath(it) } ?: firstNonBlank(o, "group", "category", "type")
        return NewsChannelConfig(id = id, label = label, url = url, group = group, path = path)
    }

    /** [raw] absolute (`http(s)://...`) is returned as-is; anything
     *  else (e.g. `"/feed/c/gh.atom"` — the real gateway's `feed` field
     *  is origin-relative, ALREADY including its own leading `/feed/`
     *  path segment) is resolved against [base]'s ORIGIN (scheme +
     *  authority only, [originOf] — never the whole [base] string
     *  verbatim). This mirrors ea_cloud-mail's already-shipping
     *  `resolveFeed`/`originOf` (CommsAccounts.java, patch 0054)
     *  exactly, and matters concretely here: [NewsSourceConfig.base]
     *  for ntfy-self is configured as `"https://rss.../feed"` (WITH the
     *  `/feed` suffix) while a real `feed` value is
     *  `"/feed/c/gh.atom"` (also WITH that prefix) — naively
     *  concatenating the two verbatim would double it into
     *  `".../feed/feed/c/gh.atom"`. Stripping to the bare origin first
     *  and letting the relative feed supply its OWN full path avoids
     *  that regardless of whether a given `base` string happens to
     *  carry a path suffix or not. */
    private fun resolveUrl(raw: String, base: String): String {
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
        val origin = originOf(base)
        if (origin.isEmpty()) return raw
        return origin + (if (raw.startsWith("/")) raw else "/$raw")
    }

    /** Scheme + authority only (e.g. `"https://rss.diegonmarcos.com"`),
     *  discarding any path/query/fragment `base` happens to carry —
     *  see [resolveUrl]'s kdoc for why the path portion must NOT
     *  survive here. "" (never throws) for a blank/unparseable base,
     *  which [resolveUrl] treats as "can't resolve, return as-is". */
    private fun originOf(base: String): String {
        if (base.isBlank()) return ""
        return runCatching {
            val uri = java.net.URI(base.trim())
            val scheme = uri.scheme
            val authority = uri.authority
            if (scheme != null && authority != null) "$scheme://$authority" else ""
        }.getOrDefault("")
    }

    /** A channel's group is every segment of its slash-separated
     *  [path] EXCEPT the leaf (the channel's own name) — e.g.
     *  `"Cloud-Infra/CICD/GH"` groups as `"Cloud-Infra/CICD"`, and a
     *  two-segment path like `"Cloud-Apps/gitea"` groups as exactly its
     *  first segment (`"Cloud-Apps"`), which is the same "drop the
     *  leaf" rule, not a separate case. A single-segment path (no `/`
     *  at all — the channel sits at the root) has no meaningful parent
     *  to group under, so this returns null (no group) rather than the
     *  leaf naming itself. Blank/empty segments (a stray leading,
     *  trailing, or doubled `/`) are dropped before the leaf/parent
     *  split so they never produce an empty group string. */
    private fun groupFromPath(path: String): String? {
        val segments = path.split("/").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size <= 1) return null
        return segments.dropLast(1).joinToString("/")
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }
}

/**
 * Decodes data/sources.json (baked into the APP module's
 * BuildConfig.SOURCES_B64) into [NewsSourceConfig]s — same
 * split-responsibility reasoning as [NewsTopicsConfig]: libs:news can't
 * reach BuildConfig directly, so callers (NewsBridge) decode the
 * Base64 and hand the raw JSON string here. Parsed results are cached
 * per-process since the underlying data never changes without a
 * rebuild.
 */
object NewsSourcesConfig {
    /** The one source the rest of the app already assumes exists
     *  (topics.json's `api.base`) — used both as the hardcoded
     *  fallback default id and as the synthesized entry a totally
     *  missing/malformed sources.json degrades to, so the app is never
     *  left with zero usable sources. */
    private const val FALLBACK_ID = "gdelt-self"

    @Volatile
    private var sourcesCache: List<NewsSourceConfig>? = null

    @Volatile
    private var defaultCache: String? = null

    /** Keys beginning with "_doc" are comments and are ignored, same
     *  convention as topics.json. Never throws: a malformed/missing
     *  file degrades to a single synthetic gdelt-self entry rather
     *  than an empty list. */
    fun parseSources(json: String): List<NewsSourceConfig> {
        sourcesCache?.let { return it }
        val parsed = runCatching {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("sources") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) return@mapNotNull null
                val capsArr = o.optJSONArray("capabilities") ?: JSONArray()
                val caps = (0 until capsArr.length()).mapNotNull { j ->
                    capsArr.optString(j, "").trim().takeIf { it.isNotEmpty() }
                }
                val label = o.optString("label", id)
                val url = o.optString("url", "")
                val queryDriven = o.optBoolean("query_driven", false)
                val dynamicChannels = o.optBoolean("dynamic_channels", false)
                val channelsArr = o.optJSONArray("channels") ?: JSONArray()
                val parsedChannels = (0 until channelsArr.length()).mapNotNull { j ->
                    val c = channelsArr.optJSONObject(j) ?: return@mapNotNull null
                    val cid = c.optString("id", "").trim()
                    if (cid.isEmpty()) return@mapNotNull null
                    NewsChannelConfig(
                        id = cid,
                        label = c.optString("label", cid),
                        url = c.optString("url", ""),
                    )
                }
                // A fixed (non-query-driven, non-dynamic) rss source
                // ALWAYS carries at least one channel by the time this
                // returns — see NewsSourceConfig's kdoc on [channels].
                // Query-driven and dynamic-channel sources are left
                // empty on purpose; their channel lists are synthesized
                // elsewhere (NewsBridge / NewsSync), each needing a
                // Context this pure parsing layer doesn't have.
                val channels = if (!queryDriven && !dynamicChannels && parsedChannels.isEmpty()) {
                    listOf(NewsChannelConfig(id = id, label = label, url = url))
                } else parsedChannels
                NewsSourceConfig(
                    id = id,
                    label = label,
                    kind = o.optString("kind", "rss"),
                    url = url,
                    queryDriven = queryDriven,
                    capabilities = caps,
                    requiresMesh = o.optBoolean("requires_mesh", false),
                    channels = channels,
                    dynamicChannels = dynamicChannels,
                    base = o.optString("base", ""),
                    channelsUrl = o.optString("channels_url", ""),
                )
            }
        }.getOrDefault(emptyList())
        val result = parsed.ifEmpty {
            listOf(
                NewsSourceConfig(
                    id = FALLBACK_ID,
                    label = "GDELT (self-hosted)",
                    kind = "gdelt",
                    url = "",
                    queryDriven = true,
                    capabilities = listOf("articles", "timeline", "tone"),
                    requiresMesh = true,
                ),
            )
        }
        sourcesCache = result
        return result
    }

    /** Falls back to [FALLBACK_ID] if `default_source` is missing or
     *  the file is malformed — never throws. */
    fun parseDefaultSource(json: String): String {
        defaultCache?.let { return it }
        val result = runCatching {
            JSONObject(json).optString("default_source", FALLBACK_ID).trim().ifEmpty { FALLBACK_ID }
        }.getOrDefault(FALLBACK_ID)
        defaultCache = result
        return result
    }
}
