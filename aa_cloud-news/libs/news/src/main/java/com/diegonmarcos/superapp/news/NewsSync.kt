package com.diegonmarcos.superapp.news

import android.content.Context

/**
 * Result of one [NewsSync.syncAll] pass. Purely informational — the
 * bridge's refreshStatus() surfaces it to the UI. Nothing here is
 * persisted; [NewsStore] is the source of truth for what actually
 * landed in the cache. Shape mirrors ea_cloud-calendar's SyncReport
 * (CalSync.kt), with `lastFetch` added since the bridge contract
 * (refreshStatus) asks for it directly.
 */
data class NewsSyncReport(
    val ok: Int,
    val failed: Int,
    val messages: List<String>,
    val lastFetch: Long,
)

/**
 * Refreshes the ACTIVE source (see [NewsSourceConfig] / SourceStore)
 * against [topics] for the query-driven paths, or [activeChannel] for
 * the fixed/dynamic-channel rss paths, and writes results into
 * [NewsStore] namespaced by that source's id. BLOCKING, synchronous
 * engine — like CalSync it does real network I/O on whatever thread
 * calls it; callers (NewsBridge) MUST run [syncAll] off the
 * main/WebView thread.
 *
 * Dispatches on [NewsSourceConfig.kind]/[NewsSourceConfig.queryDriven]/
 * [NewsSourceConfig.dynamicChannels]:
 *  - `gdelt`: the original three-endpoint-per-topic engine, unchanged
 *    in behaviour from before sources.json existed (just namespaced).
 *  - `rss` + query-driven (e.g. google-news): one feed fetch PER
 *    ENABLED TOPIC, `{query}` substituted with that topic. Each
 *    topic's channel id == the topic id itself, so this already lands
 *    on the right (source, channel) cache key with no extra work.
 *  - `rss` + dynamic-channel (ntfy-self): re-discovers the channel list
 *    from `channels_url` (see [DynamicChannels]), caches it on success
 *    (leaves the previous list untouched on failure), then fetches
 *    ONLY [activeChannel]'s feed — never all ~142 channels.
 *  - `rss`, fixed (e.g. bbc-world): fetches ONLY [activeChannel]'s
 *    declared feed — NOT every channel the source has, that would be
 *    N× pointless traffic against feeds the user isn't reading —
 *    filed under the cache key `(source.id, activeChannel)`.
 *
 * No rss path ever calls NewsStore.replaceTimeline/replaceTone: no rss
 * source has those endpoints at all (see data/sources.json's
 * capabilities), so those cache keys are simply never written for an
 * rss source id — NewsStore.timelineFor/toneFor already return empty
 * for a key that was never written, so "no timeline/tone data" falls
 * out of the existing cache-miss behaviour rather than needing special
 * casing here.
 */
object NewsSync {

    fun syncAll(
        ctx: Context,
        source: NewsSourceConfig,
        topics: List<NewsTopicState>,
        base: String,
        maxArticles: Int,
        activeChannel: String,
    ): NewsSyncReport =
        when {
            source.isGdelt -> syncGdelt(ctx, source.id, topics, base, maxArticles)
            source.isRss && source.queryDriven -> syncRssQueryDriven(ctx, source, topics, maxArticles)
            source.isRss && source.dynamicChannels -> syncRssDynamic(ctx, source, activeChannel, maxArticles)
            source.isRss -> syncRssFixed(ctx, source, activeChannel, maxArticles)
            else -> NewsSyncReport(0, 0, listOf("unknown source kind '${source.kind}'"), 0L)
        }

    /** A topic's three endpoints (articles/timeline/tone) are fetched
     *  and stored INDEPENDENTLY: one endpoint 500-ing (say, /timeline)
     *  must not discard a successful /articles fetch for the same
     *  topic, and must never touch that topic's previously-cached
     *  timeline — [NewsStore]'s replace* functions are only called
     *  after a successful individual fetch. This is stricter than
     *  CalSync's per-subscription atomicity (there, one feed = one
     *  document = one parse), because here one "topic" is really three
     *  independent server calls. Unchanged from the pre-sources.json
     *  behaviour other than every NewsStore call now carrying [sourceId]. */
    private fun syncGdelt(ctx: Context, sourceId: String, topics: List<NewsTopicState>, base: String, maxArticles: Int): NewsSyncReport {
        var ok = 0
        var failed = 0
        val messages = mutableListOf<String>()
        var latestFetch = 0L

        for (t in topics) {
            if (!t.enabled || t.topic.isBlank()) continue

            val now = System.currentTimeMillis()
            var anySuccess = false

            runCatching { NewsApi.articles(base, t.topic, maxArticles) }
                .onSuccess { NewsStore.replaceArticles(ctx, sourceId, t.topic, it); anySuccess = true }
                .onFailure { e -> messages.add("${t.topic} articles: ${e.message ?: e.javaClass.simpleName}") }

            runCatching { NewsApi.timeline(base, t.topic) }
                .onSuccess { NewsStore.replaceTimeline(ctx, sourceId, t.topic, it); anySuccess = true }
                .onFailure { e -> messages.add("${t.topic} timeline: ${e.message ?: e.javaClass.simpleName}") }

            runCatching { NewsApi.tone(base, t.topic) }
                .onSuccess { NewsStore.replaceTone(ctx, sourceId, t.topic, it); anySuccess = true }
                .onFailure { e -> messages.add("${t.topic} tone: ${e.message ?: e.javaClass.simpleName}") }

            if (anySuccess) {
                NewsStore.setLastFetch(ctx, sourceId, t.topic, now)
                if (now > latestFetch) latestFetch = now
                ok++
            } else {
                failed++
            }
        }

        // Global topic-summary list — independent of the per-topic loop
        // above; a failure here never blanks the per-topic caches, same
        // "replace only on success" rule as everything else in NewsStore.
        runCatching { NewsApi.topics(base) }
            .onSuccess { NewsStore.replaceTopicSummaries(ctx, sourceId, it) }
            .onFailure { e -> messages.add("topics: ${e.message ?: e.javaClass.simpleName}") }

        if (latestFetch == 0L) {
            latestFetch = topics.maxOfOrNull { NewsStore.lastFetch(ctx, sourceId, it.topic) } ?: 0L
        }

        return NewsSyncReport(ok, failed, messages, latestFetch)
    }

    /** One rss fetch PER ENABLED TOPIC, `source.url`'s `{query}`
     *  placeholder substituted with that topic (URL-encoded) — e.g.
     *  google-news's search feed. Each topic's fetch/parse/store is
     *  independent, same "one bad topic doesn't take down the rest"
     *  contract as [syncGdelt]. */
    private fun syncRssQueryDriven(ctx: Context, source: NewsSourceConfig, topics: List<NewsTopicState>, maxArticles: Int): NewsSyncReport {
        var ok = 0
        var failed = 0
        val messages = mutableListOf<String>()
        var latestFetch = 0L

        for (t in topics) {
            if (!t.enabled || t.topic.isBlank()) continue

            val now = System.currentTimeMillis()
            val url = source.url.replace("{query}", NewsApi.encodeQuery(t.topic))

            runCatching { RssParser.parse(NewsApi.rss(url)) }
                .onSuccess { articles ->
                    NewsStore.replaceArticles(ctx, source.id, t.topic, articles.take(maxArticles))
                    NewsStore.setLastFetch(ctx, source.id, t.topic, now)
                    if (now > latestFetch) latestFetch = now
                    ok++
                }
                .onFailure { e ->
                    messages.add("${t.topic}: ${e.message ?: e.javaClass.simpleName}")
                    failed++
                }
        }

        if (latestFetch == 0L) {
            latestFetch = topics.maxOfOrNull { NewsStore.lastFetch(ctx, source.id, it.topic) } ?: 0L
        }

        return NewsSyncReport(ok, failed, messages, latestFetch)
    }

    /** A fixed source's declared channel (front page / section — no
     *  query term to vary) is fetched ONCE per refresh, never once per
     *  channel and never once per enabled topic — just [activeChannel].
     *  Deliberately does NOT take a `topics` parameter — that keeps a
     *  future caller from accidentally looping this per-topic like the
     *  other two sync paths. [source.channels] is guaranteed non-empty
     *  by [NewsSourcesConfig.parseSources] (see that fun's kdoc), so
     *  the `?: source.channels.first()` fallback below only ever fires
     *  for a stale/unknown [activeChannel] id (e.g. a rebuild dropped a
     *  channel the user had picked), never for a genuinely-empty list. */
    private fun syncRssFixed(ctx: Context, source: NewsSourceConfig, activeChannel: String, maxArticles: Int): NewsSyncReport {
        val channel = source.channels.firstOrNull { it.id == activeChannel } ?: source.channels.first()
        val url = channel.url.ifBlank { source.url }
        val now = System.currentTimeMillis()
        return runCatching { RssParser.parse(NewsApi.rss(url)) }
            .fold(
                onSuccess = { articles ->
                    NewsStore.replaceArticles(ctx, source.id, channel.id, articles.take(maxArticles))
                    NewsStore.setLastFetch(ctx, source.id, channel.id, now)
                    NewsSyncReport(ok = 1, failed = 0, messages = emptyList(), lastFetch = now)
                },
                onFailure = { e ->
                    NewsSyncReport(
                        ok = 0,
                        failed = 1,
                        messages = listOf("${channel.id}: ${e.message ?: e.javaClass.simpleName}"),
                        lastFetch = NewsStore.lastFetch(ctx, source.id, channel.id),
                    )
                },
            )
    }

    /** ntfy-self's sync path: re-discovers the FULL channel list from
     *  [NewsSourceConfig.channelsUrl] on every call (channels come and
     *  go as the user's self-hosted infrastructure changes), caches it
     *  to [DynamicChannelStore] ONLY on a successful parse (same
     *  replace-only-on-success rule as everywhere else in this file —
     *  see that store's kdoc), then fetches exactly [activeChannel]'s
     *  feed — never all ~142 discovered channels in one refresh. A
     *  discovery failure or an activeChannel absent from both the
     *  freshly-discovered and previously-cached lists is reported as a
     *  message, never a crash and never a fabricated placeholder
     *  channel. If the payload's own `counts.total` (see
     *  [DynamicChannels.Result.totalCount]) disagrees with how many
     *  channels actually parsed, that discrepancy is ALSO reported as a
     *  message — turns a silent partial parse into something
     *  diagnosable instead of a channel list that's just quietly
     *  short. */
    private fun syncRssDynamic(ctx: Context, source: NewsSourceConfig, activeChannel: String, maxArticles: Int): NewsSyncReport {
        val messages = mutableListOf<String>()

        val discoveryResult = runCatching { DynamicChannels.parseResult(NewsApi.channels(source.channelsUrl), source.base) }
            .onFailure { e -> messages.add("channels: ${e.message ?: e.javaClass.simpleName}") }
            .getOrNull()
        val discovered = discoveryResult?.channels
        if (!discovered.isNullOrEmpty()) {
            DynamicChannelStore.replace(ctx, source.id, discovered)
            val total = discoveryResult.totalCount
            if (total != null && total != discovered.size) {
                messages.add("channels: parsed ${discovered.size} of $total channels")
            }
        } else if (discovered != null) {
            messages.add("channels: discovery returned no channels")
        }

        val channels = discovered?.takeIf { it.isNotEmpty() } ?: DynamicChannelStore.channelsFor(ctx, source.id)
        val channel = channels.firstOrNull { it.id == activeChannel } ?: channels.firstOrNull()
        if (channel == null || channel.url.isBlank()) {
            messages.add("no channel available for '$activeChannel'")
            return NewsSyncReport(
                ok = 0,
                failed = 1,
                messages = messages,
                lastFetch = NewsStore.lastFetch(ctx, source.id, activeChannel),
            )
        }

        val now = System.currentTimeMillis()
        return runCatching { RssParser.parse(NewsApi.rss(channel.url)) }
            .fold(
                onSuccess = { articles ->
                    NewsStore.replaceArticles(ctx, source.id, channel.id, articles.take(maxArticles))
                    NewsStore.setLastFetch(ctx, source.id, channel.id, now)
                    NewsSyncReport(ok = 1, failed = 0, messages = messages, lastFetch = now)
                },
                onFailure = { e ->
                    messages.add("${channel.id}: ${e.message ?: e.javaClass.simpleName}")
                    NewsSyncReport(
                        ok = 0,
                        failed = 1,
                        messages = messages,
                        lastFetch = NewsStore.lastFetch(ctx, source.id, channel.id),
                    )
                },
            )
    }
}
