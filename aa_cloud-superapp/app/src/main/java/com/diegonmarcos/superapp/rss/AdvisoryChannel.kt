package com.diegonmarcos.superapp.rss

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The advisory topic's recent messages, read for DISPLAY on the RSS screen.
 *
 * ## Why this is not `recovery/AdvisoryFeed`
 * That one is the background poll: it runs unattended, throttles itself to an
 * hour, ingests into [com.diegonmarcos.superapp.updater.Advisory] and drives
 * the banner and the notification. It is deliberately narrow — it only keeps
 * messages matching the structured advisory contract, and it keeps no text.
 *
 * This one is the opposite end: a user has OPENED the channel and is looking
 * at it. Everything published there should be visible, including the plain
 * `curl -d 'text'` posts that the contract parser drops on purpose. A person
 * who navigated here on the advice of "check my-rss" and found an empty page
 * would conclude the channel is dead, and the channel being trusted is the
 * entire reason it exists.
 *
 * ## Why an install link becomes an ACTION, not a browser tab
 * A URL ending in `.apk` handed to the browser downloads a file the user then
 * has to find, tap, and push through an unknown-sources prompt with no
 * verification anywhere in the chain. The app already owns a path that
 * downloads, checks the sha256 sidecar and hands verified bytes to the system
 * installer. So a message carrying a link routes to THAT
 * ([com.diegonmarcos.superapp.recovery.RecoveryActivity]) and the link itself
 * is shown as provenance, not as the mechanism.
 */
object AdvisoryChannel {

    private const val BASE = "https://rss.diegonmarcos.com"

    /** One message as the channel view shows it. [link] is non-null when the
     *  message carries something installable. */
    data class Message(
        val title: String,
        val body: String,
        val link: String?,
        /** Fleet app id the advisory is about, when it says. Null means "this
         *  one" — the recovery list already puts the running package first. */
        val appId: String?,
        val timeSeconds: Long,
    )

    /** Any http(s) URL in the text; an `.apk` one wins over a plain one so a
     *  message that cites both a release page and the artifact installs the
     *  artifact. */
    private val URL_RE = Regex("""https?://\S+""")

    private fun linkIn(vararg texts: String?): String? {
        val all = texts.filterNotNull().flatMap { URL_RE.findAll(it).map { m -> m.value.trimEnd('.', ',', ')', '"', '\'') } }
        return all.firstOrNull { it.endsWith(".apk", ignoreCase = true) } ?: all.firstOrNull()
    }

    /**
     * Recent messages, newest first. Blocking; call off the main thread.
     * Throws on network failure so the caller can say WHY the channel is
     * empty — "couldn't reach it" and "nothing published" are different
     * answers and only one of them means "keep waiting".
     */
    fun recent(topic: String, days: Int = 30): List<Message> {
        val c = (URL("$BASE/$topic/json?poll=1&since=${days}d").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code")

        return body.lineSequence()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("event") == "message" }
            .map { o ->
                val raw = o.optString("message")
                // A structured advisory renders from its own fields; anything
                // else renders as what it is. Neither shape is dropped.
                val structured = runCatching { JSONObject(raw) }.getOrNull()
                    ?.takeIf { it.optString("kind") == "fleet-advisory" }
                Message(
                    title = structured?.optString("title")?.ifBlank { null }
                        ?: o.optString("title").ifBlank { topic },
                    body = structured?.optString("detail")?.ifBlank { null } ?: raw,
                    link = linkIn(structured?.optString("link"), raw, o.optString("click")),
                    appId = structured?.optString("app")?.ifBlank { null },
                    timeSeconds = o.optLong("time"),
                )
            }
            .toList()
            .asReversed()
    }
}
