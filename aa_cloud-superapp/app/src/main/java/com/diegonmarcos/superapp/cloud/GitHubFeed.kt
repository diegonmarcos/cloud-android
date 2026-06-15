package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.AggregatorStackFragment

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny GitHub REST API client. Unauthenticated (60 req/h limit, fine for
 * a handful of repos × 60s cache), JSON parsed via org.json. Used by the
 * AggregatorStackFragment "repos" and "gha_runs" panel kinds.
 *
 * Cache is a SharedPreferences blob per "<owner>/<repo>/<kind>" key with
 * a 60s TTL — repeated re-renders of the Infos page don't hammer GitHub.
 *
 * Returns simple data classes (Commit / Run) instead of leaking JSON
 * objects to the UI layer.
 */
object GitHubFeed {
    private const val PREFS = "gh_feed_cache"
    private const val TTL_MS = 60_000L
    private const val UA = "Diego-SuperApp/1.0 (+https://diegonmarcos.com)"

    data class Commit(
        val sha: String,
        val message: String,
        val author: String,
        val htmlUrl: String,
        val tsMillis: Long,
    )

    data class Run(
        val name: String,
        val displayTitle: String,
        val status: String,
        val conclusion: String,
        val htmlUrl: String,
        val tsMillis: Long,
    )

    suspend fun commits(ctx: Context, owner: String, repo: String, perPage: Int = 5): List<Commit> {
        val key  = "$owner/$repo/commits"
        val body = fetchCached(ctx, key,
            "https://api.github.com/repos/$owner/$repo/commits?per_page=$perPage")
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val c = o.getJSONObject("commit")
                val author = c.optJSONObject("author")?.optString("name").orEmpty()
                Commit(
                    sha       = o.optString("sha", "").take(7),
                    message   = c.optString("message", "").lineSequence().firstOrNull().orEmpty(),
                    author    = author,
                    htmlUrl   = o.optString("html_url", ""),
                    tsMillis  = parseIso8601(c.optJSONObject("author")?.optString("date").orEmpty()),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun runs(ctx: Context, owner: String, repo: String, perPage: Int = 5): List<Run> {
        val key  = "$owner/$repo/runs"
        val body = fetchCached(ctx, key,
            "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=$perPage")
            ?: return emptyList()
        return runCatching {
            val outer = org.json.JSONObject(body)
            val arr   = outer.optJSONArray("workflow_runs") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Run(
                    name          = o.optString("name", ""),
                    displayTitle  = o.optString("display_title", "").lineSequence().firstOrNull().orEmpty(),
                    status        = o.optString("status", ""),
                    conclusion    = o.optString("conclusion", ""),
                    htmlUrl       = o.optString("html_url", ""),
                    tsMillis      = parseIso8601(o.optString("created_at", "")),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Read from SharedPreferences if fresh; otherwise fetch + store. */
    private suspend fun fetchCached(ctx: Context, key: String, url: String): String? {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val ts  = sp.getLong("$key.ts", 0L)
        if (now - ts < TTL_MS) {
            val cached = sp.getString("$key.body", null)
            if (cached != null) return cached
        }
        val body = fetch(url) ?: return sp.getString("$key.body", null) // fall back to stale on net error
        sp.edit()
            .putLong("$key.ts", now)
            .putString("$key.body", body)
            .apply()
        return body
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout    = 5_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun parseIso8601(s: String): Long = runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)

    /** Format a millis-ago value as a compact "<x>m ago / <x>h ago / …" hint. */
    fun ago(ms: Long): String = when {
        ms < 60_000     -> "just now"
        ms < 3_600_000  -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else            -> "${ms / 86_400_000}d ago"
    }
}
