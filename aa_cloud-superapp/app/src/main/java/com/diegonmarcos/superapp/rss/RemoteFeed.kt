package com.diegonmarcos.superapp.rss

import android.content.Context
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONArray
import org.json.JSONObject

/** One feed entry, as the UI needs it. The app's own type on purpose: it no
 *  longer links libs:feed, so it does not see RssClient.Item. */
data class FeedItem(val title: String, val link: String, val date: String)

/**
 * The app's front end onto Cloud-Lib-Feed.apk.
 *
 * Replaces the direct `RssClient.fetch(url)` call. Fetching and parsing RSS
 * vs Atom now happen in the engine APK; this side receives JSON and builds
 * view models. Returns an empty list when the engine is absent or the feed
 * fails - a feed that will not load should empty a pane, not crash a screen.
 *
 * Off the main thread only: bind and network both block. Callers already ran
 * RssClient.fetch on a worker, so that requirement is unchanged.
 */
object RemoteFeed {

    private const val ENGINE_PKG = "com.diegonmarcos.cloudlib.feed"
    private const val ENGINE_SVC = "com.diegonmarcos.superapp.feed.FeedBackendService"

    @Volatile private var client: DataBackendClient? = null

    private fun client(ctx: Context): DataBackendClient =
        client ?: synchronized(this) {
            client ?: DataBackendClient(ctx, ENGINE_PKG, ENGINE_SVC).also { client = it }
        }

    fun isEngineInstalled(ctx: Context): Boolean = client(ctx).isInstalled()

    fun fetch(ctx: Context, url: String, maxItems: Int = 25): List<FeedItem> {
        val json = client(ctx).call("fetch", url, maxItems.toString())
        // DataBackendClient answers errors as {"error": ...}, i.e. an object
        // where success is an array - so a parse failure IS the error path.
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                add(FeedItem(
                    title = o.optString("title"),
                    link  = o.optString("link"),
                    date  = o.optString("date"),
                ))
            }
        }
    }
}
