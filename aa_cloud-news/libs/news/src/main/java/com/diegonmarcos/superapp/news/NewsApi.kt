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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
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
