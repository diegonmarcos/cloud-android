package com.diegonmarcos.superapp.recovery

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.updater.Advisory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE OUT-OF-BAND CHANNEL — the only one that can reach a device that is
 * already stranded.
 *
 * ## Why this is not redundant with the banner and the notification
 * Both of those are built from the INSTALLED build's strings and the INSTALLED
 * build's logic. A device stuck on a three-week-old APK can only ever say what
 * that APK already knew how to say, and if the thing that is broken is the
 * update chain itself, no amount of in-app messaging will ever be newer than
 * the problem.
 *
 * This channel is server-side and pull-based. An advisory published to the
 * topic today is received by every device that still polls it — no app update
 * in between, no privileged access, no pairing. For a fleet of thousands whose
 * update path is the broken part, that is the difference between reachable and
 * unreachable, and it is why this is worth its own poll rather than being
 * folded into the RSS screen the user has to go looking for.
 *
 * ## Who writes the feed
 * NOT this app, and deliberately so — a publisher living inside the app would
 * be exactly as stale as the app. The topic is an ntfy topic on the existing
 * `rss.diegonmarcos.com` gateway (the same host `rss/RssFeedFragment.kt`
 * already renders topics from), so publishing an advisory to the whole fleet
 * is one server-side call with no deploy at all:
 *
 *     curl -d '{"kind":"fleet-advisory","id":"2026-09-05-superapp-stuck",
 *               "app":"cloud-superapp","severity":"STUCK",
 *               "title":"SuperApp updates are stuck",
 *               "detail":"Open the app and tap Fix it — install directly.",
 *               "link":"https://github.com/…/CloudSuperApp.apk"}' \
 *          https://rss.diegonmarcos.com/[TOPIC]
 *
 * The message schema is [Advisory.feedContract], parsed by
 * [Advisory.parseFeedMessage]. A message that is not that shape is IGNORED —
 * the topic can carry other traffic without half-rendering into a warning.
 */
object AdvisoryFeed {

    private const val TAG = "Advisory/Feed"

    /**
     * The ntfy topic advisories are published to.
     *
     * Baked into the build, which is a real limitation and worth naming: if
     * this string is ever wrong, no already-shipped device can be told the
     * right one. That is acceptable ONLY because it is a constant that never
     * needs to change — the escape hatch's address is not a policy knob. Every
     * value that CAN reasonably change (which app, what happened, what to do,
     * where the APK is) travels in the message, not in the build.
     */
    private const val TOPIC = "fleet_advisory"

    private const val BASE = "https://rss.diegonmarcos.com"

    /** Poll at most this often. The advisory is not time-critical to the
     *  minute, and a stranded phone is usually a phone on a bad network. */
    private const val MIN_INTERVAL_MS = 60L * 60L * 1000L

    /**
     * Fetch the topic's recent messages and hand any advisories to [Advisory].
     * Blocking; call from a worker thread. Never throws: this runs on the
     * unhappy path by definition, and a crash here removes the last channel.
     */
    fun poll(ctx: Context) {
        val p = ctx.getSharedPreferences("advisory_feed", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0L) < MIN_INTERVAL_MS) return
        p.edit().putLong("last", now).apply()
        runCatching {
            // ntfy's poll endpoint answers newline-delimited JSON and closes,
            // rather than holding a stream open — the right shape for a
            // background check that must not hold a socket on a bad network.
            val c = (URL("$BASE/$TOPIC/json?poll=1&since=7d").openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val body = if (c.responseCode in 200..299)
                c.inputStream.bufferedReader().use { it.readText() } else ""
            c.disconnect()
            val items = body.lineSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { it.optString("event") == "message" }
                .mapNotNull { Advisory.parseFeedMessage(it.optString("message")) }
                .toList()
            // Ingest even when empty: an advisory that has been RETRACTED
            // server-side must disappear from the device, and it only can if
            // an empty answer overwrites the stored list.
            Advisory.ingestExternal(ctx, items)
        }.onFailure { Log.i(TAG, "advisory poll skipped: ${it.message}") }
    }
}
