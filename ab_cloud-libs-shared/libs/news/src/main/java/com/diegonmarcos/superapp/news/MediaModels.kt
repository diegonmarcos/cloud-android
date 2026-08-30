package com.diegonmarcos.superapp.news

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MEDIA (YouTube channels) — the data half of the Media tab. A YouTube
 * channel publishes a per-channel Atom feed at
 * `https://www.youtube.com/feeds/videos.xml?channel_id=<ID>`, which
 * RssParser.kt already parses (Atom is one of its three supported
 * shapes — see that file's class kdoc for the yt:videoId/media:thumbnail
 * handling added alongside this). So a YouTube channel is deliberately
 * modelled as one [NewsChannelConfig] of a single synthetic
 * `youtube-media` [NewsSourceConfig] ([asSource]) — this is the SAME
 * "fixed rss source, N declared channels, only the active one ever
 * fetched" shape data/sources.json's bbc-world/guardian-world/etc.
 * already use, which is exactly what lets [NewsSync.syncRssFixed] and
 * [NewsStore]'s per-(source,channel) cache be reused completely
 * unchanged for media instead of a parallel media-sync engine.
 *
 * Deliberately NOT folded into data/sources.json / [NewsSourcesConfig]:
 * the Media tab is its own top-level bottom-nav destination (Media ·
 * Events · Feed · Saved · Configs), not another entry in the news
 * source picker, so its config is its own file/BuildConfig field
 * (data/media.json -> BuildConfig.MEDIA_B64) baked and decoded the
 * same split-responsibility way as topics.json/sources.json — see
 * those files' kdocs.
 */
data class MediaChannelConfig(
    val id: String,
    val label: String,
    /** The raw YouTube channel id (`UC...`), as it appears in the
     *  channel's own URL — kept distinct from [id] (this app's stable
     *  slug/key) because a slug is nicer for cache keys/UI selection
     *  while the raw id is what the feed URL and the bridge's
     *  `channelId` field actually need. */
    val channelId: String,
) {
    /** RssParser already handles Atom — see that file's class kdoc —
     *  so this is the existing rss path pointed at a different URL
     *  shape, never a second parser. */
    val feedUrl: String get() = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
}

/**
 * Decodes data/media.json (baked into the APP module's
 * BuildConfig.MEDIA_B64) into [MediaChannelConfig]s, same split
 * responsibility as [NewsTopicsConfig]/[NewsSourcesConfig] — libs:news
 * can't reach BuildConfig itself, so callers (NewsBridge) decode the
 * Base64 and hand the raw JSON string here.
 */
object MediaConfig {
    /** Cache key / [NewsSourceConfig.id] every YouTube channel's
     *  articles are namespaced under in [NewsStore] — distinct from
     *  every data/sources.json id (none of which is "youtube-media"). */
    const val SOURCE_ID = "youtube-media"

    @Volatile
    private var channelsCache: List<MediaChannelConfig>? = null

    /** Keys beginning with "_doc" are comments and are ignored, same
     *  convention as topics.json/sources.json. Never throws: a
     *  malformed/missing file degrades to an empty channel list rather
     *  than crashing the app — [asSource] then falls back to a single
     *  placeholder channel, same "never leave the app with nothing
     *  usable" reasoning as [NewsSourcesConfig.parseSources]. */
    fun parseChannels(json: String): List<MediaChannelConfig> {
        channelsCache?.let { return it }
        val result = runCatching {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("channels") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "").trim()
                val channelId = o.optString("channel_id", "").trim()
                if (id.isEmpty() || channelId.isEmpty()) return@mapNotNull null
                MediaChannelConfig(id = id, label = o.optString("label", id), channelId = channelId)
            }
        }.getOrDefault(emptyList())
        channelsCache = result
        return result
    }

    /** Wraps [parseChannels] as a [NewsSourceConfig] so [NewsSync]'s
     *  existing `syncRssFixed` path (fetch ONLY the active channel,
     *  never every channel in one refresh) can be called verbatim for
     *  media the exact same way NewsBridge already calls it for
     *  bbc-world/guardian-world/etc. — see this file's class kdoc. */
    fun asSource(json: String): NewsSourceConfig {
        val channels = parseChannels(json).map { NewsChannelConfig(id = it.id, label = it.label, url = it.feedUrl) }
        return NewsSourceConfig(
            id = SOURCE_ID,
            label = "YouTube",
            kind = "rss",
            url = "",
            queryDriven = false,
            capabilities = listOf("articles"),
            requiresMesh = false,
            channels = channels.ifEmpty { listOf(NewsChannelConfig(id = SOURCE_ID, label = "YouTube", url = "")) },
        )
    }
}

/**
 * Decides which ONE media channel [com.diegonmarcos.cloudnews.NewsBridge].refresh
 * fetches on a given call, via plain round-robin. Why round-robin and
 * not a persisted "active channel" the way [ChannelStore] does for a
 * fixed data/sources.json rss source: this bridge's `mediaItems(channel,
 * limit)` takes an explicit channel id argument on every call and is
 * otherwise stateless — there is no single UI-driven "current media
 * channel" selection to read back the way a source's second nav row
 * has one. Round-robin still bounds each refresh() to exactly one
 * channel fetch (never all 8 — see that fun's kdoc) while guaranteeing
 * every channel gets refreshed at least once every `channels.size`
 * calls, with no channel starved indefinitely.
 */
object MediaRotationStore {
    private const val PREFS = "media_rotation"
    private const val KEY_INDEX = "next_index"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns the channel to fetch THIS call (null if [channels] is
     *  empty) and advances the stored index for next time in the same
     *  call, so two callers can't observe/consume the same index. */
    fun nextChannel(ctx: Context, channels: List<MediaChannelConfig>): MediaChannelConfig? {
        if (channels.isEmpty()) return null
        val p = prefs(ctx)
        val stored = p.getInt(KEY_INDEX, 0)
        val idx = if (stored < 0 || stored >= channels.size) 0 else stored
        p.edit().putInt(KEY_INDEX, (idx + 1) % channels.size).apply()
        return channels[idx]
    }
}
