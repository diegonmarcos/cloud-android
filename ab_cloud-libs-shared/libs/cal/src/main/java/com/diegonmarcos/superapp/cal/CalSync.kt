package com.diegonmarcos.superapp.cal

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Result of one [CalSync.syncAll] pass. Purely informational — the
 * caller (a notification, a debug screen, a log line) decides what to
 * do with it. Nothing here is persisted; [CalendarStore] is the
 * source of truth for what actually landed in the cache.
 */
data class SyncReport(
    val ok: Int,
    val skipped: Int,
    val failed: Int,
    val messages: List<String>,
)

/**
 * Fetches every enabled [CalSubscription], parses its ICS body with
 * [IcsParser], and writes the result into [CalendarStore]. This is a
 * BLOCKING, synchronous engine — it does real network I/O on whatever
 * thread calls it. Callers MUST run [syncAll] off the main thread
 * (a WorkManager job, an ExecutorService, etc.); it does not spawn
 * its own background thread because the app already has its own
 * conventions for that (see other engines in this repo) and this
 * module intentionally stays dependency-free.
 */
object CalSync {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun syncAll(ctx: Context, subs: List<CalSubscription>): SyncReport {
        var ok = 0
        var skipped = 0
        var failed = 0
        val messages = mutableListOf<String>()

        for (sub in subs) {
            if (!sub.enabled || sub.url.isBlank()) {
                skipped++
                continue
            }

            val now = System.currentTimeMillis()
            val last = CalendarStore.lastSync(ctx, sub.id)
            val refreshMs = sub.refreshHours.toLong() * 60 * 60 * 1000
            if (last > 0 && now - last < refreshMs) {
                skipped++
                continue
            }

            // Every subscription is isolated: one feed timing out or
            // 500-ing must never take down the rest of the sync pass,
            // so each iteration's failure is caught right here rather
            // than let bubbling up out of syncAll.
            runCatching { syncOne(ctx, sub, now) }
                .onSuccess { changed ->
                    ok++
                    if (!changed) messages.add("${sub.id}: unchanged (304)")
                }
                .onFailure { e ->
                    failed++
                    messages.add("${sub.id}: ${e.message ?: e.javaClass.simpleName}")
                }
        }

        return SyncReport(ok, skipped, failed, messages)
    }

    /** Returns true if new events were written, false if the server
     *  reported 304 Not Modified (cache left untouched). Throws on
     *  any other failure — caller in [syncAll] catches it. */
    private fun syncOne(ctx: Context, sub: CalSubscription, now: Long): Boolean {
        val conn = (URL(sub.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/calendar, text/plain, */*")
            CalendarStore.etag(ctx, sub.id)?.let { setRequestProperty("If-None-Match", it) }
        }

        try {
            val status = conn.responseCode

            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                // Server confirms our cached copy is still current —
                // this is a SUCCESS path, just with nothing to parse.
                CalendarStore.setLastSync(ctx, sub.id, now)
                return false
            }

            if (status !in 200..299) {
                error("HTTP $status")
            }

            val body = conn.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            }

            val events = IcsParser.parse(body, sub.id)
            CalendarStore.replaceEvents(ctx, sub.id, events)
            CalendarStore.setEtag(ctx, sub.id, conn.getHeaderField("ETag"))
            CalendarStore.setLastSync(ctx, sub.id, now)
            return true
        } finally {
            conn.disconnect()
        }
    }
}
