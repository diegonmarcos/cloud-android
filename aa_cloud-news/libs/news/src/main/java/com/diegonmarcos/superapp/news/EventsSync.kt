package com.diegonmarcos.superapp.news

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Result of one [EventsSync.syncAll] pass. Purely informational — same
 *  shape as ea_cloud-calendar's `SyncReport` (CalSync.kt). Nothing here
 *  is persisted; [EventsStore] is the source of truth for what actually
 *  landed in the cache. */
data class EventsSyncReport(
    val ok: Int,
    val skipped: Int,
    val failed: Int,
    val messages: List<String>,
)

/**
 * Fetches every ENABLED [EventCalendarConfig], parses its ICS body with
 * [IcsParser], and writes the result into [EventsStore]. Ported
 * field-for-field from ea_cloud-calendar's `CalSync` (CalSync.kt),
 * including its per-calendar `refresh_hours` throttle — see that
 * throttle's role in [com.diegonmarcos.cloudnews.NewsBridge].refresh's
 * kdoc for why this is what keeps a steady-state refresh() call cheap
 * even with ~20 calendars configured: [syncOne] is only actually
 * invoked for a calendar whose own `refresh_hours` window has elapsed
 * since [EventsStore.lastSync], everything else is skipped. The FIRST
 * ever call (empty cache, `lastSync == 0` for every calendar) is the
 * one exception — every enabled calendar is "due" then, which is
 * correct: the cache has to be populated from somewhere.
 *
 * BLOCKING, synchronous — does real network I/O on whatever thread
 * calls it. Callers (NewsBridge) MUST run this off the main/WebView
 * thread, same contract as [NewsSync].
 */
object EventsSync {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun syncAll(ctx: Context, calendars: List<EventCalendarConfig>): EventsSyncReport {
        var ok = 0
        var skipped = 0
        var failed = 0
        val messages = mutableListOf<String>()

        for (cal in calendars) {
            if (!cal.enabled || cal.url.isBlank()) {
                skipped++
                continue
            }

            val now = System.currentTimeMillis()
            val last = EventsStore.lastSync(ctx, cal.id)
            val refreshMs = cal.refreshHours.toLong() * 60 * 60 * 1000
            if (last > 0 && now - last < refreshMs) {
                skipped++
                continue
            }

            // Every calendar is isolated: one feed timing out or
            // 500-ing must never take down the rest of the sync pass,
            // same "one bad feed doesn't blank the rest" contract as
            // NewsSync's per-topic loops.
            runCatching { syncOne(ctx, cal, now) }
                .onSuccess { changed ->
                    ok++
                    if (!changed) messages.add("${cal.id}: unchanged (304)")
                }
                .onFailure { e ->
                    failed++
                    messages.add("${cal.id}: ${e.message ?: e.javaClass.simpleName}")
                }
        }

        return EventsSyncReport(ok, skipped, failed, messages)
    }

    /** Returns true if new events were written, false if the server
     *  reported 304 Not Modified (cache left untouched, same as a
     *  success). Throws on any other failure — caller in [syncAll]
     *  catches it, leaving the previous cache intact (offline-first). */
    private fun syncOne(ctx: Context, cal: EventCalendarConfig, now: Long): Boolean {
        val conn = (URL(cal.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/calendar, text/plain, */*")
            setRequestProperty("User-Agent", "CloudNews/1.0 (+https://diegonmarcos.com)")
            EventsStore.etag(ctx, cal.id)?.let { setRequestProperty("If-None-Match", it) }
        }

        try {
            val status = conn.responseCode

            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                EventsStore.setLastSync(ctx, cal.id, now)
                return false
            }

            if (status !in 200..299) {
                error("HTTP $status")
            }

            val body = conn.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            }

            val events = IcsParser.parse(body, cal.id)
            EventsStore.replaceEvents(ctx, cal.id, events)
            EventsStore.setEtag(ctx, cal.id, conn.getHeaderField("ETag"))
            EventsStore.setLastSync(ctx, cal.id, now)
            return true
        } finally {
            conn.disconnect()
        }
    }
}
