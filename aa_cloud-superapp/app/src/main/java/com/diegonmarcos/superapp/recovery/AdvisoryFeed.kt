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
 *          https://rss.diegonmarcos.com/fleet_advisory
 *
 * and withdrawing it is the same call with the same id:
 *
 *     curl -d '{"kind":"fleet-advisory","id":"2026-09-05-superapp-stuck",
 *               "retract":true}' https://rss.diegonmarcos.com/fleet_advisory
 *
 * The message schema is [Advisory.feedContract], parsed by
 * [Advisory.parseFeedMessage]. A message that is not that shape is IGNORED —
 * the topic can carry other traffic without half-rendering into a warning.
 *
 * ntfy wraps the published document as a STRING in the `message` field of its
 * own envelope, so [poll] parses the envelope and then the payload. That is
 * unwrapping the transport, not double-parsing: reading the envelope as the
 * advisory would find `kind` absent and silently discard every message.
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

    /**
     * How far back to ask. `all`, and the choice matters more than it looks.
     *
     * ntfy accepts a duration (units `s`/`m`/`h` — NOT `d`), a Unix timestamp,
     * a message id, or `all`. `7d` is none of those: it answered HTTP 400
     * `{"code":40008,"error":"invalid since parameter"}` on every single poll,
     * which is how this arrived here in the first place.
     *
     * Of the three that work, `all` is the only one that does not put a SECOND,
     * invisible expiry policy on the device. A window (`168h`, a timestamp)
     * means an advisory silently stops being delivered once it ages out — the
     * device would quietly forget a problem that is still true, and nobody
     * publishing to the topic would have any way to know a week was the limit.
     * `all` asks for whatever the server still holds, so retention is decided
     * in exactly one place: ntfy's own cache duration, server-side, where it
     * can be changed without shipping an APK. That is the same principle as
     * the rest of this file — the escape hatch must not bake policy into a
     * build that stranded devices cannot replace.
     *
     * The cost is that advisories no longer expire themselves, so RETRACTION
     * IS NOW EXPLICIT: publish the same `id` again with `"retract": true` and
     * the last word wins. That is a feature, not a chore — an advisory that
     * vanished because a timer ran out was indistinguishable from one that was
     * deliberately withdrawn, and only one of those means "this is resolved".
     */
    private const val SINCE = "all"

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
            val c = (URL("$BASE/$TOPIC/json?poll=1&since=$SINCE").openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                // DO NOT FOLLOW REDIRECTS. The topic host sits behind an
                // Authelia forward_auth, and an anonymous device — which is
                // every device this channel exists for — is answered 302 to
                // auth.diegonmarcos.com. Followed, that lands on the login page
                // as HTTP 200 with an HTML body: a "success" containing no
                // messages, i.e. indistinguishable from "there are no
                // advisories", which under the retract-on-empty rule would
                // CLEAR a live advisory instead of showing it. Unfollowed, the
                // 302 is a loud error naming the real problem.
                instanceFollowRedirects = false
            }
            val code = c.responseCode
            // A NON-2xx MUST BE LOUD, AND MUST NOT LOOK LIKE "no advisories".
            //
            // This read `if (code in 200..299) … else ""`, and the empty string
            // then flowed into the retract-on-empty path below — so a server
            // that was REFUSING us cleared the advisory instead of raising one.
            // The last-resort channel could be broken and the only symptom was
            // silence, which is the exact failure mode this whole feature
            // exists to end, reproduced inside the thing meant to end it.
            //
            // It was not hypothetical: `since=7d` is not valid ntfy syntax
            // (units are s/m/h only; `7d` parses as neither a duration nor a
            // message id), so every poll answered
            // `{"code":40008,"http":400,"error":"invalid since parameter"}`
            // and every device silently concluded there was nothing to say.
            if (code !in 200..299) {
                val err = runCatching {
                    c.errorStream?.bufferedReader()?.use { it.readText() }?.trim()
                }.getOrNull().orEmpty()
                c.disconnect()
                // Thrown, so it lands in onFailure with the body attached, and
                // NOTHING is ingested — the previously stored advisory survives
                // a failed poll rather than being cleared by it.
                error("HTTP $code from $BASE/$TOPIC ${err.take(200)}")
            }
            val type = c.contentType.orEmpty()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            // PROVE IT IS AN ANSWER FROM NTFY BEFORE BELIEVING IT IS AN EMPTY
            // ONE. Belt and braces with the redirect guard above, because any
            // future interstitial — a captive portal, a CDN error page, a
            // proxy's own 200 — has the same shape: a successful response full
            // of something that is not our protocol, which parses to zero
            // messages and reads as "all clear". The retract-on-empty rule
            // makes that actively destructive, so an empty list is only
            // honoured when the response was demonstrably ntfy's.
            if (!type.contains("json", ignoreCase = true))
                error("expected JSON from $BASE/$TOPIC, got '$type' — the topic is " +
                      "probably behind an auth interstitial, which a stranded device " +
                      "cannot sign in to. Body began: ${body.take(120)}")
            val all = body.lineSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { it.optString("event") == "message" }
                // Double-encoded on purpose: ntfy's envelope carries our JSON
                // document as the "message" STRING, so this is unwrapping the
                // transport, not parsing the payload twice.
                .mapNotNull { Advisory.parseFeedMessage(it.optString("message")) }
                .toList()
            // LAST WORD PER ID WINS, and a retraction is a word. See [SINCE]:
            // the window no longer expires advisories on its own, so clearing
            // one is an explicit publish rather than the passage of time.
            val live = all.associateBy { it.id }.values.filter { !it.retract }
            Log.i(TAG, "advisory poll: HTTP $code, ${all.size} message(s) → ${live.size} live")
            Advisory.ingestExternal(ctx, live)
        }.onFailure {
            // WARN, not INFO. This is the channel of last resort; a device
            // whose only remaining lifeline is failing should say so at a
            // level someone reading `logcat -s Advisory/Feed` will actually see.
            Log.w(TAG, "advisory poll FAILED (stored advisories left intact): ${it.message}")
        }
    }
}
