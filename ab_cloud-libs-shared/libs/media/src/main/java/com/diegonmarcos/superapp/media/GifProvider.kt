package com.diegonmarcos.superapp.media

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GifProvider"

/** Hide the api key before a URL reaches logcat. */
private fun redact(spec: String): String = spec.replace(Regex("(api_key|key)=[^&]*"), "$1=***")

/**
 * A GIF search backend. Two impls, chosen data-driven by
 * build.json::keyboard_media.gif.provider. Both are blocking — call from a
 * background thread (MediaPanelView does). Errors return an empty list; the
 * panel shows its empty state rather than crashing the keyboard.
 */
interface GifProvider {
    /** [query] blank → the provider's trending/featured feed. */
    fun fetch(query: String, limit: Int): List<MediaItem>

    companion object {
        fun from(conf: MediaRuntime.GifConf): GifProvider =
            if (conf.provider == "giphy") Giphy(conf.baseUrl, conf.apiKey)
            else Tenor(conf.baseUrl, conf.apiKey)
    }
}

private fun httpGetJson(spec: String): JSONObject? = runCatching {
    val c = (URL(spec).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
    }
    try {
        val code = c.responseCode
        if (code != 200) {
            // Make an empty GIF tab diagnosable instead of silent: a bad/missing key
            // is 401/403, a rate limit is 429. Without this the failure is
            // indistinguishable from "the search genuinely had no results".
            val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            Log.w(TAG, "GIF fetch HTTP $code ${redact(spec)} ${body?.take(200).orEmpty()}")
            return null
        }
        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally { c.disconnect() }
}.onFailure { Log.w(TAG, "GIF fetch failed ${redact(spec)}: ${it.message}") }.getOrNull()

/** Tenor v2 (Google). Trending = /featured, else /search. */
private class Tenor(private val base: String, private val key: String) : GifProvider {
    override fun fetch(query: String, limit: Int): List<MediaItem> {
        val endpoint = if (query.isBlank()) "featured" else "search"
        val url = Uri.parse("$base/$endpoint").buildUpon()
            .appendQueryParameter("key", key)
            .appendQueryParameter("limit", limit.toString())
            .appendQueryParameter("media_filter", "tinygif,gif")
            .appendQueryParameter("client_key", "cloud_superapp")
            .apply { if (query.isNotBlank()) appendQueryParameter("q", query) }
            .build().toString()
        val results = httpGetJson(url)?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val fmts = results.optJSONObject(i)?.optJSONObject("media_formats") ?: return@mapNotNull null
            val preview = fmts.optJSONObject("tinygif")?.optString("url").orEmpty()
            val source = fmts.optJSONObject("gif")?.optString("url").orEmpty()
            if (preview.isBlank() || source.isBlank()) null
            else MediaItem(preview, source, "image/gif", results.optJSONObject(i)!!.optString("content_description"))
        }
    }
}

/** Giphy. Trending = /trending, else /search. */
private class Giphy(private val base: String, private val key: String) : GifProvider {
    override fun fetch(query: String, limit: Int): List<MediaItem> {
        val endpoint = if (query.isBlank()) "trending" else "search"
        val url = Uri.parse("$base/$endpoint").buildUpon()
            .appendQueryParameter("api_key", key)
            .appendQueryParameter("limit", limit.toString())
            .apply { if (query.isNotBlank()) appendQueryParameter("q", query) }
            .build().toString()
        val data = httpGetJson(url)?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val images = data.optJSONObject(i)?.optJSONObject("images") ?: return@mapNotNull null
            val preview = images.optJSONObject("fixed_width_small")?.optString("url").orEmpty()
            val source = images.optJSONObject("original")?.optString("url").orEmpty()
            if (preview.isBlank() || source.isBlank()) null
            else MediaItem(preview, source, "image/gif", data.optJSONObject(i)!!.optString("title"))
        }
    }
}
