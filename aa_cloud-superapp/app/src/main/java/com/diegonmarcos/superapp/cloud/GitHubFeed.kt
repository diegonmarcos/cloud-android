package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny GitHub REST API client. Unauthenticated, JSON parsed via org.json.
 * Used by the AggregatorStackFragment "repos" and "gha_runs" panel kinds.
 *
 * Cache is a SharedPreferences blob per "<owner>/<repo>/<kind>" key.
 *
 * BUDGET: the anonymous quota is 60 requests per hour PER IP, shared by
 * every panel and every repo. The repos panel alone now carries the whole
 * cloud + front family, and gha_runs carries three more, so one cold page
 * open costs about a dozen requests — at the old 60s TTL that exhausted
 * the hour's quota in five page opens and GitHub started answering 403.
 * Hence [TTL_MS] is fifteen minutes: a page re-opened inside that window
 * costs zero requests, which is the common case for a page the user taps
 * in and out of. Callers additionally stagger their fan-out so a cold
 * open is a trickle rather than a simultaneous burst.
 *
 * FAILURE IS NEVER SILENT: an exhausted quota and a genuinely empty repo
 * are different facts and must never render the same. Every call returns
 * a [Feed] carrying both the items and the [Status] that produced them,
 * so the UI can say "rate limited" when that is what happened instead of
 * shrugging and printing "no commits".
 *
 * Returns simple data classes (Commit / Run) instead of leaking JSON
 * objects to the UI layer.
 */
object GitHubFeed {
    private const val PREFS = "gh_feed_cache"
    private const val TTL_MS = 900_000L
    private const val UA = "Diego-SuperApp/1.0 (+https://diegonmarcos.com)"

    /** Why a feed looks the way it does. [OK] means GitHub answered (an
     *  empty item list then genuinely means "nothing to show").
     *  [RATE_LIMITED] means the 60/h anonymous quota is spent.
     *  [UNREACHABLE] covers everything else — no network, DNS, 404, 5xx. */
    enum class Status { OK, RATE_LIMITED, UNREACHABLE }

    /** Items plus the reason they are what they are. When [status] is not
     *  [Status.OK] but [items] is non-empty, these are STALE cached items
     *  being shown rather than nothing — the panel says so. */
    data class Feed<T>(val items: List<T>, val status: Status)

    /** Per-request delay used by callers to stagger a fan-out across the
     *  repo list instead of firing every repo at GitHub at once. */
    const val STAGGER_MS = 120L

    private class Fetched(val body: String?, val status: Status)

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
        /** Workflow file name (".github/workflows/ship.yml" → "ship.yml").
         *  GitHub's dispatch endpoint is addressed by this file name, so it
         *  is what lets a row offer a re-run trigger. Empty when the run
         *  payload carried no path. */
        val workflowFile: String = "",
    )

    suspend fun commits(ctx: Context, owner: String, repo: String, perPage: Int = 5): Feed<Commit> {
        val key  = "$owner/$repo/commits"
        val res  = fetchCached(ctx, key,
            "https://api.github.com/repos/$owner/$repo/commits?per_page=$perPage")
        val body = res.body ?: return Feed(emptyList(), res.status)
        return Feed(runCatching {
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
        }.getOrDefault(emptyList()), res.status)
    }

    suspend fun runs(ctx: Context, owner: String, repo: String, perPage: Int = 5): Feed<Run> {
        val key  = "$owner/$repo/runs"
        val res  = fetchCached(ctx, key,
            "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=$perPage")
        val body = res.body ?: return Feed(emptyList(), res.status)
        return Feed(runCatching {
            val outer = org.json.JSONObject(body)
            val arr   = outer.optJSONArray("workflow_runs")
                ?: return Feed(emptyList(), res.status)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Run(
                    name          = o.optString("name", ""),
                    displayTitle  = o.optString("display_title", "").lineSequence().firstOrNull().orEmpty(),
                    status        = o.optString("status", ""),
                    conclusion    = o.optString("conclusion", ""),
                    htmlUrl       = o.optString("html_url", ""),
                    tsMillis      = parseIso8601(o.optString("created_at", "")),
                    workflowFile  = o.optString("path", "").substringAfterLast('/'),
                )
            }
        }.getOrDefault(emptyList()), res.status)
    }

    /** Read from SharedPreferences if fresh; otherwise fetch + store.
     *
     *  On a failed fetch the stale cached body is still served — a
     *  fifteen-minute-old commit list beats a blank panel — but the
     *  FAILURE status travels with it, so the caller can mark the rows
     *  stale instead of passing them off as current. */
    private suspend fun fetchCached(ctx: Context, key: String, url: String): Fetched {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val ts  = sp.getLong("$key.ts", 0L)
        if (now - ts < TTL_MS) {
            val cached = sp.getString("$key.body", null)
            if (cached != null) return Fetched(cached, Status.OK)
        }
        val fresh = fetch(url)
        if (fresh.body == null) {
            return Fetched(sp.getString("$key.body", null), fresh.status)
        }
        sp.edit()
            .putLong("$key.ts", now)
            .putString("$key.body", fresh.body)
            .apply()
        return fresh
    }

    private suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout    = 5_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = conn.responseCode
                when {
                    code in 200..299 ->
                        Fetched(conn.inputStream.bufferedReader().use { it.readText() }, Status.OK)
                    // GitHub reports an exhausted quota as 403 (legacy) or 429
                    // (current) WITH x-ratelimit-remaining: 0. The same codes
                    // without that header mean a real permission refusal, which
                    // is a different problem and must not be mislabelled.
                    (code == 403 || code == 429) &&
                        conn.getHeaderField("x-ratelimit-remaining") == "0" ->
                        Fetched(null, Status.RATE_LIMITED)
                    else -> Fetched(null, Status.UNREACHABLE)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Fetched(null, Status.UNREACHABLE) }
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
