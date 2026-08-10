package com.diegonmarcos.cloudnews

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import com.diegonmarcos.superapp.news.GdeltArticle
import com.diegonmarcos.superapp.news.NewsApiConfig
import com.diegonmarcos.superapp.news.NewsConfigStore
import com.diegonmarcos.superapp.news.NewsStore
import com.diegonmarcos.superapp.news.NewsSync
import com.diegonmarcos.superapp.news.NewsTopicConfig
import com.diegonmarcos.superapp.news.NewsTopicsConfig
import com.diegonmarcos.superapp.news.NewsTopicsStore
import com.diegonmarcos.superapp.news.SavedStore
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

    // ---- topics -----------------------------------------------------------

    @JavascriptInterface
    fun topics(): String {
        val effective = NewsTopicsStore.effective(ctx, bakedTopics())
        val summariesByTopic = NewsStore.topicSummaries(ctx).associateBy { it.topic }
        val arr = JSONArray()
        for (state in effective) {
            val summary = summariesByTopic[state.topic]
            val cachedArticles = if (summary == null) NewsStore.articlesFor(ctx, state.topic) else emptyList()
            val articleCount = summary?.articleCount ?: cachedArticles.size
            val avgTone = summary?.avgTone
                ?: (if (cachedArticles.isNotEmpty()) cachedArticles.map { it.tone }.average() else 0.0)
            arr.put(JSONObject().apply {
                put("topic", state.topic)
                put("label", state.label)
                put("articleCount", articleCount)
                put("avgTone", avgTone)
                put("lastFetch", NewsStore.lastFetch(ctx, state.topic).toString())
                put("enabled", state.enabled)
            })
        }
        return arr.toString()
    }

    /** topic "" means the merged feed across all enabled topics, newest
     *  first (GDELT's seendate is a zero-padded string, so a plain
     *  descending string sort is already chronological — see
     *  NewsStore.replaceArticles). limit falls back to the effective
     *  config's maxArticles if blank/unparseable. */
    @JavascriptInterface
    fun articles(topic: String, limit: String): String {
        val limitInt = limit.toIntOrNull()?.takeIf { it > 0 } ?: effectiveConfig().maxArticles
        val list = if (topic.isBlank()) {
            val enabledTopics = NewsTopicsStore.effective(ctx, bakedTopics()).filter { it.enabled }.map { it.topic }
            NewsStore.articlesFor(ctx, enabledTopics)
        } else {
            NewsStore.articlesFor(ctx, topic)
        }
        val sorted = list.sortedByDescending { it.seendate }.take(limitInt)
        val arr = JSONArray()
        for (a in sorted) arr.put(a.toJson())
        return arr.toString()
    }

    @JavascriptInterface
    fun timeline(topic: String): String {
        val arr = JSONArray()
        if (topic.isBlank()) return arr.toString()
        for (p in NewsStore.timelineFor(ctx, topic)) arr.put(p.toJson())
        return arr.toString()
    }

    @JavascriptInterface
    fun tone(topic: String): String {
        val arr = JSONArray()
        if (topic.isBlank()) return arr.toString()
        for (t in NewsStore.toneFor(ctx, topic)) arr.put(t.toJson())
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

    @JavascriptInterface
    fun removeTopic(topic: String): String {
        val error = NewsTopicsStore.removeTopic(ctx, topic, bakedTopics())
        if (error.isEmpty()) NewsStore.clearTopic(ctx, topic)
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
                    val topics = NewsTopicsStore.effective(ctx, bakedTopics())
                    val report = NewsSync.syncAll(ctx, topics, cfg.base, cfg.maxArticles)
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
