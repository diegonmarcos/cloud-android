package com.diegonmarcos.superapp.news

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Talks to the self-hosted GDELT proxy described in data/topics.json's
 * `api` block (V_front-configs/c-LabTools/news/src/typescript/api.ts is
 * the TS original). Same house style as libs:cal's CalSync — plain
 * HttpURLConnection, no third-party HTTP client, connect+read timeouts,
 * one GET per call. Unlike CalSync this is stateless (no ETag/If-None-
 * Match — the proxy doesn't support conditional GETs) and returns
 * parsed lists directly rather than writing to a store; NewsSync owns
 * deciding what to do with the result.
 *
 * Every fetch* function THROWS on network/HTTP failure (caller catches,
 * same contract as CalSync.syncOne) but never throws over a malformed
 * response BODY: a non-2xx status is the only thing that raises here,
 * everything about parsing degrades gracefully — see [envelopeArray].
 */
object NewsApi {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "CloudNews/1.0 (+https://diegonmarcos.com)"

    fun articles(base: String, topic: String, limit: Int): List<GdeltArticle> {
        val body = get("${base.trimEnd('/')}/articles?q=${enc(topic)}&limit=$limit")
        val arr = envelopeArray(body, "articles")
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { GdeltArticle.fromJson(o) }.getOrNull() }
        }
    }

    fun timeline(base: String, topic: String): List<TimelinePoint> {
        val body = get("${base.trimEnd('/')}/timeline?q=${enc(topic)}")
        val arr = envelopeArray(body, "timeline")
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { TimelinePoint.fromJson(o) }.getOrNull() }
        }
    }

    fun tone(base: String, topic: String): List<ToneEntry> {
        val body = get("${base.trimEnd('/')}/tone?q=${enc(topic)}")
        val arr = envelopeArray(body, "tone")
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { ToneEntry.fromJson(o) }.getOrNull() }
        }
    }

    fun topics(base: String): List<TopicSummary> {
        val body = get("${base.trimEnd('/')}/topics")
        val arr = envelopeArray(body, "topics")
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> runCatching { TopicSummary.fromJson(o) }.getOrNull() }
        }
    }

    /**
     * Fetches a raw RSS 2.0 / RSS 1.0(RDF) / Atom document from one of
     * data/sources.json's `kind: "rss"` entries — see RssParser.kt for
     * the parse side. Same plain HttpURLConnection/timeouts/User-Agent
     * as [get] above, just a feed-flavored Accept header instead of
     * `application/json`, and (unlike [articles]/[timeline]/[tone]/
     * [topics]) talking straight to a third-party publisher instead of
     * our own self-hosted proxy — see the PRIVACY note below.
     *
     * PRIVACY: switching the app's active source away from `gdelt-self`
     * to e.g. Google News/BBC/DW/etc. means THIS request goes straight
     * from the handset to that publisher's own server. That publisher
     * now sees the user's IP address and, over repeated refreshes,
     * which topics/feeds get requested and how often — a real
     * reading-interest signal. The self-hosted GDELT proxy is the whole
     * point of self-hosting: those requests never reach a third party
     * at all. That is a genuine trade-off the user opts into the
     * moment they call NewsBridge.setSource with a non-gdelt id, not a
     * bug to "fix" here. This function does not make it any worse than
     * necessary though: no tracking/analytics query params are added,
     * no `Referer` header is sent (HttpURLConnection never sets one
     * unless told to, and nothing here tells it to), and the
     * User-Agent is the exact same plain [USER_AGENT] string used for
     * the GDELT proxy — no extra fingerprinting surface beyond what an
     * ordinary feed-reader request already exposes.
     */
    fun rss(urlStr: String): String =
        get(urlStr, accept = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")

    /**
     * Fetches a `dynamic_channels: true` source's channel-discovery
     * JSON (ntfy-self's rss-gateway `/feed/channels.json`) — same plain
     * GET as [rss] above, just a JSON Accept header, and handed to
     * [DynamicChannels.parseResult] rather than [RssParser.parse]. Same
     * PRIVACY note as [rss] would apply if this endpoint were public,
     * but it isn't: `requires_mesh: true` on ntfy-self means this only
     * ever runs against the user's own self-hosted rss-gateway.
     *
     * NO AUTH HEADER, ON PURPOSE: the same rss-gateway software (see
     * ea_cloud-mail's SUPER RSS READER patches) has an optional
     * Bearer-token config for OTHER deployments of it
     * (`comms_cloud_token`), but data/sources.json's ntfy-self entry
     * records this deployment as `feed_auth=none` — so no
     * Authorization header is ever sent from here. A future deployment
     * that DOES require one would need a token slot threaded through
     * [NewsSourceConfig]/data/sources.json first, not silently added
     * to this call.
     */
    fun channels(urlStr: String): String = get(urlStr)

    /** URL-encodes [s] for safe interpolation into a query string —
     *  exposed (the GDELT endpoints above use the private [enc]
     *  directly) so [NewsSync] can substitute a topic into a
     *  query-driven rss source's `{query}` placeholder the exact same
     *  way. */
    fun encodeQuery(s: String): String = enc(s)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(urlStr: String, accept: String = "application/json"): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) error("HTTP $status")
            return conn.inputStream.use { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The four endpoints all wrap their array under a named key in an
     * envelope object (`{query,count,articles:[...]}`, etc. — see
     * types.ts). Real-world proxies drift though, so this also accepts
     * a bare JSON array at the top level. Any other shape — or invalid
     * JSON entirely — degrades to an empty array rather than throwing;
     * a garbled body must not take down the whole sync pass (same
     * philosophy as IcsParser skipping one bad VEVENT).
     */
    private fun envelopeArray(body: String, key: String): JSONArray {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return JSONArray()
        return runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray(key) ?: JSONArray()
        }.getOrDefault(JSONArray())
    }
}
