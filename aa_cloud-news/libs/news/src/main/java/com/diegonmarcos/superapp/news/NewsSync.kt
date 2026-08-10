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
 * Refreshes every enabled topic's articles/timeline/tone against the
 * configured API base, plus the global /topics summary, and writes
 * results into [NewsStore]. BLOCKING, synchronous engine — like
 * CalSync it does real network I/O on whatever thread calls it;
 * callers (NewsBridge) MUST run [syncAll] off the main/WebView thread.
 *
 * A topic's three endpoints (articles/timeline/tone) are fetched and
 * stored INDEPENDENTLY: one endpoint 500-ing (say, /timeline) must not
 * discard a successful /articles fetch for the same topic, and must
 * never touch that topic's previously-cached timeline — [NewsStore]'s
 * replace* functions are only called after a successful individual
 * fetch. This is stricter than CalSync's per-subscription atomicity
 * (there, one feed = one document = one parse), because here one
 * "topic" is really three independent server calls.
 */
object NewsSync {

    fun syncAll(ctx: Context, topics: List<NewsTopicState>, base: String, maxArticles: Int): NewsSyncReport {
        var ok = 0
        var failed = 0
        val messages = mutableListOf<String>()
        var latestFetch = 0L

        for (t in topics) {
            if (!t.enabled || t.topic.isBlank()) continue

            val now = System.currentTimeMillis()
            var anySuccess = false

            runCatching { NewsApi.articles(base, t.topic, maxArticles) }
                .onSuccess { NewsStore.replaceArticles(ctx, t.topic, it); anySuccess = true }
                .onFailure { e -> messages.add("${t.topic} articles: ${e.message ?: e.javaClass.simpleName}") }

            runCatching { NewsApi.timeline(base, t.topic) }
                .onSuccess { NewsStore.replaceTimeline(ctx, t.topic, it); anySuccess = true }
                .onFailure { e -> messages.add("${t.topic} timeline: ${e.message ?: e.javaClass.simpleName}") }

            runCatching { NewsApi.tone(base, t.topic) }
                .onSuccess { NewsStore.replaceTone(ctx, t.topic, it); anySuccess = true }
                .onFailure { e -> messages.add("${t.topic} tone: ${e.message ?: e.javaClass.simpleName}") }

            if (anySuccess) {
                NewsStore.setLastFetch(ctx, t.topic, now)
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
            .onSuccess { NewsStore.replaceTopicSummaries(ctx, it) }
            .onFailure { e -> messages.add("topics: ${e.message ?: e.javaClass.simpleName}") }

        if (latestFetch == 0L) {
            latestFetch = topics.maxOfOrNull { NewsStore.lastFetch(ctx, it.topic) } ?: 0L
        }

        return NewsSyncReport(ok, failed, messages, latestFetch)
    }
}
