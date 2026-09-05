package com.diegonmarcos.superapp.recovery

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.rss.NtfyCatalog
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
 * the problem. This channel is server-side and pull-based: an advisory
 * published today reaches every device that still polls, with no app update in
 * between.
 *
 * ## The endpoint is the MESH IP, not the public hostname
 * Every fleet device is a WireGuard mesh member, so `10.0.0.6:8090` is
 * reachable from all of them and is the ntfy service itself.
 *
 * `rss.diegonmarcos.com` is the same service behind the public edge, and that
 * route is `wg_only` with an Authelia `forward_auth` in front. Polling it
 * anonymously returns 302 to the SSO login page — and, followed, HTTP 200 with
 * an HTML body: a "success" containing no messages, which under the
 * retract-on-empty rule would CLEAR a live advisory instead of showing one.
 * Two paths to one service, only one of them gated. The resolution is to use
 * the ungated path, not to open the gated one — the gate is correct.
 *
 * Cleartext to `10.0.0.6` is already permitted by
 * `res/xml/network_security_config.xml`, which enumerates the mesh peers.
 * Verified, not assumed: Android blocks cleartext by default and that failure
 * surfaces as `CLEARTEXT communication not permitted`, which looks nothing
 * like an HTTP error and would have been a second silent death.
 *
 * ## Publishing
 *     curl -d '{"kind":"fleet-advisory","id":"2026-09-05-superapp-stuck",
 *               "app":"cloud-superapp","severity":"STUCK",
 *               "title":"Update stuck",
 *               "detail":"Open the app and tap Fix it - install directly.",
 *               "link":"https://github.com/…/Cloud-SuperApp.apk"}' \
 *          http://10.0.0.6:8090/fleet_advisory
 *
 * Withdraw with the same id and `"retract":true`.
 * Schema: [Advisory.feedContract], parsed by [Advisory.parseFeedMessage].
 */
object AdvisoryFeed {

    private const val TAG = "Advisory/Feed"

    /** The ntfy service on the mesh, ahead of the public edge's auth gate. */
    private const val BASE = "http://10.0.0.6:8090"

    /**
     * The STRUCTURED topic: machine-readable [Advisory.feedContract] documents,
     * which is what the banner and the notification render.
     *
     * Hard-coded rather than read from build.json because it is the address of
     * the escape hatch, not a policy knob — a device too stale to update is
     * also too stale to learn a new topic name, so a value that could change
     * is a value that could strand it. Everything that CAN reasonably change
     * (which app, what happened, what to do, where the APK is) travels in the
     * message.
     */
    private const val STRUCTURED_TOPIC = "fleet_advisory"

    /**
     * Both topics, deduplicated.
     *
     * MEASURED, not assumed, and the reason this is a list: `build.json`
     * declares `ui.ntfy.advisory_topic = cloud-sa-notifications`, and that
     * topic carries HUMAN PROSE ("Update-chain fix is published (build
     * 3356276…)") for the RSS channel screen to render. It is not the
     * contract. Polling only it would parse to zero advisories, and — because
     * a valid 200 is authoritative — would AUTHORITATIVELY RETRACT every live
     * advisory. The same silent-clear failure as the 400 and the SSO 302, from
     * a third direction.
     *
     * Polling only [STRUCTURED_TOPIC] would instead miss an advisory raised on
     * the topic the config actually names. So: both, merged. Mixed traffic is
     * already expected — the contract says a message that is not the right
     * shape is ignored rather than half-rendered — so a prose message on
     * either topic costs nothing, and a structured one on either is delivered.
     */
    private fun topics(): List<String> =
        listOf(STRUCTURED_TOPIC, NtfyCatalog.advisoryTopic()).distinct()

    /** Poll at most this often. Not time-critical to the minute, and a
     *  stranded phone is usually a phone on a bad network. */
    private const val MIN_INTERVAL_MS = 60L * 60L * 1000L

    /**
     * One topic's answer, and the reason it is a type rather than a bare list.
     *
     * A `Fetch` with no items means "this topic answered, and there are
     * genuinely no advisories" — that CAN clear stored ones. A `null` means
     * "this topic could not tell me", which must change nothing. Collapsing
     * those two into an empty list is precisely the bug that made a 400, a
     * 302-to-login and a fake 200 all read as all-clear, and a `List` cannot
     * hold the difference.
     */
    private class Fetch(val items: List<Advisory.Item>)

    fun poll(ctx: Context) {
        val p = ctx.getSharedPreferences("advisory_feed", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0L) < MIN_INTERVAL_MS) return
        p.edit().putLong("last", now).apply()

        val answers = topics().map { it to fetch(it) }

        // NOTHING IS INGESTED UNLESS SOMETHING COULD ACTUALLY ANSWER. Every
        // topic failing is a real event with a real consequence — the lifeline
        // is down — and it must never be spelled the same way as "all clear",
        // which under retract-on-empty would erase a live warning.
        if (answers.all { it.second == null }) {
            Log.w(TAG, "advisory poll FAILED on all ${answers.size} topic(s); " +
                       "stored advisories left intact")
            return
        }
        // Last word per id wins across every topic that answered. With
        // since=all there is no server-side expiry doing this implicitly any
        // more, so a retraction is an explicit message and this is the only
        // thing that resolves it.
        val merged = answers.flatMap { it.second?.items ?: emptyList() }
            .associateBy { it.id }
            .values
            .filter { !it.retract }
        Log.i(TAG, "advisory poll: " +
                   answers.joinToString(" ") { (t, f) ->
                       "$t=${f?.items?.size?.toString() ?: "unreachable"}"
                   } + " -> ${merged.size} live")
        Advisory.ingestExternal(ctx, merged)
    }

    /**
     * Fetch one topic. Returns null when it could not be believed — loudly,
     * with the reason — and a [Fetch] when it could.
     *
     * `since=all`, and the form matters: ntfy accepts a duration in s/m/h (NOT
     * d), a Unix timestamp, a message id, or `all`. The original `since=7d` is
     * none of those and answered HTTP 400
     * `{"code":40008,"error":"invalid since parameter"}` on every poll, so
     * every device silently concluded there was nothing to say.
     *
     * Of the forms that work, `all` is the only one that does not put a second,
     * invisible expiry on the device. Retention is then ntfy's own cache
     * duration — measured at 30 days on this instance — decided server-side
     * where it can change without shipping an APK, which is the whole point of
     * an escape hatch stranded devices cannot replace.
     */
    private fun fetch(topic: String): Fetch? = runCatching {
        val c = (URL("$BASE/$topic/json?poll=1&since=all").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            // NO REDIRECTS. On the mesh path there is no legitimate hop at all
            // — ntfy answers directly — so any 3xx means we are not talking to
            // ntfy, and following it is how the public path turned an SSO
            // bounce into a 200 full of HTML. Unfollowed, it is a loud error
            // naming a real misconfiguration.
            instanceFollowRedirects = false
        }
        val code = c.responseCode
        // A NON-2xx MUST BE LOUD, AND MUST NOT LOOK LIKE "no advisories". This
        // read `if (code in 200..299) … else ""`, and the empty string flowed
        // straight into the retract-on-empty path — so a server actively
        // refusing us CLEARED the advisory instead of raising one.
        if (code !in 200..299) {
            val err = runCatching {
                c.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty().trim()
            c.disconnect()
            error("HTTP $code ${err.take(200)}")
        }
        val type = c.contentType.orEmpty()
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        // PROVE IT IS NTFY BEFORE BELIEVING IT IS EMPTY. Any interstitial — a
        // captive portal, a proxy's own 200, an error page — is a successful
        // response full of something that is not our protocol, which parses to
        // zero messages and reads as all-clear. ntfy answers
        // `application/x-ndjson` here (measured), which satisfies this.
        if (!type.contains("json", ignoreCase = true))
            error("expected JSON, got '$type'; body began: ${body.take(120)}")
        Fetch(parse(body))
    }.onFailure {
        // WARN, not INFO: this is the channel of last resort, and a device
        // whose lifeline is failing should say so where `logcat -s
        // Advisory/Feed` will show it.
        Log.w(TAG, "topic '$topic' unusable: ${it.message}")
    }.getOrNull()

    /**
     * ntfy's newline-delimited envelopes, each carrying our document as the
     * `message` STRING. The two-stage parse is unwrapping the transport, not
     * parsing twice — reading the envelope as the advisory would find `kind`
     * absent and discard every message.
     */
    private fun parse(body: String): List<Advisory.Item> =
        body.lineSequence()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .filter { it.optString("event") == "message" }
            .mapNotNull { Advisory.parseFeedMessage(it.optString("message")) }
            .toList()
}
