package com.diegonmarcos.superapp.updater

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * WHAT THE DEVICE KNOWS IS WRONG WITH ITS OWN UPDATE CHAIN, and what the user
 * can do about it.
 *
 * ## The failure this ends
 * A stranded device was SILENT. `Fleet.commit` threw "no install channel
 * accepted <pkg>" into logcat, the caller turned it into a Toast that said the
 * same thing, and that was the entire user-visible record of a phone that could
 * no longer update itself — an unrecoverable dead end phrased as a transient
 * error. Across thousands of devices nobody would ever report it, because from
 * the user's side nothing appears to be happening at all.
 *
 * This is the state that makes that condition VISIBLE and ACTIONABLE. It is the
 * one model behind three surfaces:
 *
 *  - the in-app home banner (primary — cannot be swiped away, silenced, or
 *    blocked at the OS level, and is necessarily seen on the next app open),
 *  - the advisory notification (secondary — reaches a user who never opens the
 *    app, which is exactly the stranded user),
 *  - the ntfy/RSS feed (out-of-band — see [ingestExternal]; the ONLY one that
 *    can deliver NEW words to a device whose installed build is too old to know
 *    how to describe its own problem).
 *
 * Every one of them ends at the same action: [BootstrapInstall].
 *
 * ## Engine, not UI
 * No R, no views, no notification building — the host owns all three surfaces.
 * This owns the trigger conditions, the dedupe, and the rate limit, because
 * those are exactly the parts that must not be re-derived three times and drift.
 */
object Advisory {

    private const val TAG = "Updater/Advisory"
    private const val PREFS = "updater_advisory"

    /** Consecutive failures for one app before it is worth telling the user.
     *  One failure is a bad network; three in a row is a broken chain. */
    const val FAILURE_THRESHOLD = 3

    /** Staleness that is worth a banner on its own, even with no failure
     *  recorded — a device whose worker never runs fails silently by never
     *  failing at all. Seven days is far past the normal ship cadence and far
     *  short of "the user was on holiday with the phone off". */
    const val STALE_DAYS = 7L

    /** Never re-raise the same advisory more often than this. The banner is
     *  rebuilt on every resume; without a floor the notification would re-post
     *  on every one of them. */
    private const val RENOTIFY_MS = 6L * 60L * 60L * 1000L

    /** How bad, and therefore which surfaces should carry it. */
    enum class Severity { INFO, WARN, STUCK }

    /**
     * One thing to tell the user.
     *
     * [appId] is the fleet id the recovery screen should preselect, or null for
     * an advisory that is not about one app (an out-of-band notice, say). [id]
     * is the dedupe key: same id means same advisory, however many times the
     * condition is re-detected.
     */
    data class Item(
        val id: String,
        val appId: String?,
        val title: String,
        val detail: String,
        val severity: Severity,
        /** Where this came from: "local" or "feed". A feed advisory is the only
         *  kind whose words are newer than the installed build, so the UI is
         *  entitled to say so. */
        val source: String,
        /** Optional direct URL for the "self healing link" — set by a feed
         *  advisory that names an APK the app's own sources cannot reach. */
        val link: String? = null,
        /** A TOMBSTONE: this advisory is withdrawn. Only ever set by the feed,
         *  because only the feed needs it — a locally-derived advisory
         *  disappears when the condition that produced it does, but a published
         *  one has no such condition to watch and must be explicitly recalled.
         *  Carried on the item rather than sent as a separate message type so
         *  that "raise X" and "clear X" are the same document with one field
         *  different, and last-word-per-id resolves them without ordering
         *  rules. */
        val retract: Boolean = false,
        /** When the message was POSTED, from the ntfy envelope's `time`, in
         *  millis. Zero for a locally-derived advisory, which has no publish
         *  time because it is recomputed from live conditions on every read.
         *  Drives the staleness bound in [tooOld]. */
        val publishedAtMs: Long = 0L,
    )

    /**
     * A message somebody typed, turned into a banner.
     *
     * ## Plain text is the primary case
     * This started as a strict JSON contract, and that was the wrong product.
     * It is a MESSAGE BOARD: the operator posts a sentence and every phone
     * shows it. Requiring a schema meant an ordinary `curl -d 'Maintenance
     * tonight 22:00'` produced nothing at all — silently, because a message
     * that did not parse was "ignored rather than half-rendered" — and what
     * did appear read like an error the app emitted rather than a note a human
     * wrote.
     *
     * So: any non-empty message becomes a banner. No required fields, no
     * wrapper, no ceremony. ntfy's own optional `title` is used when present
     * because ntfy already has that concept and posting with `-H "Title: …"`
     * is natural; otherwise the first line becomes the heading and the rest
     * the body, which is how people write notes anyway.
     *
     * ## The structured form still works, because it costs one branch
     * A message that happens to be the old `fleet-advisory` JSON is rendered
     * as before, including `retract` and `severity`. That is worth keeping for
     * the automated recovery advisories — but it is now the special case, not
     * the price of entry.
     *
     * [envelope] is ntfy's own JSON line, not the payload: the message id and
     * the publish time live there, and both matter (the id is what a dismissal
     * is keyed on, the time is what expires it).
     */
    fun fromNtfy(envelope: JSONObject): Item? {
        val text = envelope.optString("message").trim()
        if (text.isEmpty()) return null
        val postedMs = envelope.optLong("time", 0L) * 1000L
        // Structured first — it is strictly more specific, so if it parses it
        // is what the publisher meant.
        structured(text, postedMs)?.let { return it }
        val ntfyTitle = envelope.optString("title").trim()
        val head = if (ntfyTitle.isNotEmpty()) ntfyTitle else text.lineSequence().first().trim()
        val body = if (ntfyTitle.isNotEmpty()) text
                   else text.substringAfter('\n', "").trim()
        return Item(
            // The ntfy message id, so a dismissal is per POST. Editing the
            // wording and re-posting is a new message and shows again, which
            // is what a notice board should do — otherwise a corrected notice
            // would stay hidden behind the dismissal of the wrong one.
            id = envelope.optString("id").takeIf { it.isNotBlank() } ?: text.hashCode().toString(),
            appId = null,
            title = head,
            detail = body,
            // A human note is not an emergency. Severity is what tints the
            // banner and gates the notification; a posted message should look
            // like a message, not like the app is broken.
            severity = Severity.INFO,
            source = "feed",
            link = firstUrl(text),
            publishedAtMs = postedMs,
        )
    }

    /** The old strict contract, when a message happens to be one. */
    private fun structured(body: String, postedMs: Long): Item? = runCatching {
        val o = JSONObject(body)
        if (o.optString("kind") != "fleet-advisory") return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val retract = o.optBoolean("retract", false)
        Item(
            id = id,
            appId = o.optString("app").takeIf { it.isNotBlank() },
            // A retraction only has to identify what it withdraws, so it is
            // not held to the title requirement a raise is — demanding one
            // would mean an advisory could be published but never recalled.
            title = o.optString("title").takeIf { it.isNotBlank() }
                ?: (if (retract) "" else return null),
            detail = o.optString("detail"),
            severity = runCatching { Severity.valueOf(o.optString("severity")) }
                .getOrDefault(Severity.WARN),
            source = "feed",
            link = o.optString("link").takeIf { it.isNotBlank() },
            retract = retract,
            publishedAtMs = postedMs,
        )
    }.getOrNull()

    /**
     * Any http(s) URL in the posted text, `.apk` winning over a plain one.
     *
     * Costs the poster nothing — a message with no URL simply shows no button
     * — and turns "the fix is at <link>" from something the user has to
     * retype into something they can tap. An `.apk` link is routed through the
     * recovery screen by the UI rather than the browser, so the verified
     * download path is not skipped just because the URL arrived in prose.
     */
    private fun firstUrl(text: String): String? {
        val found = Regex("""https?://\S+""").findAll(text)
            .map { it.value.trimEnd('.', ',', ')', '"', '\'', '>') }
            .toList()
        return found.firstOrNull { it.endsWith(".apk", ignoreCase = true) } ?: found.firstOrNull()
    }

    // ── Trigger 1: repeated install failure for the same app ────────────────

    /**
     * Record that installing [appId] failed, with the reason the caller was
     * given. Below [FAILURE_THRESHOLD] this only counts; at or above it the
     * device starts saying so.
     *
     * [reason] is stored verbatim and shown verbatim. "no install channel
     * accepted com.diegonmarcos.superapp" told the user nothing, but it told a
     * reader everything — the fix is to pair it with an action, not to hide it.
     */
    fun recordFailure(ctx: Context, appId: String, label: String, reason: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getInt("fail_$appId", 0) + 1
        p.edit()
            .putInt("fail_$appId", n)
            .putString("why_$appId", reason)
            .putString("label_$appId", label)
            .putLong("at_$appId", System.currentTimeMillis())
            .apply()
        Log.w(TAG, "$appId: consecutive failure #$n — $reason")
    }

    /** Clear [appId]'s failure record. Called on a successful install: an
     *  advisory that outlives the problem it describes is noise, and noise is
     *  how the next real one gets ignored. */
    fun recordSuccess(ctx: Context, appId: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getInt("fail_$appId", 0) == 0) return
        p.edit()
            .remove("fail_$appId").remove("why_$appId")
            .remove("at_$appId").remove("shown_$appId")
            .apply()
        Log.i(TAG, "$appId: recovered — advisory cleared")
    }

    // ── Trigger 2: a pass that could not act at all ─────────────────────────

    /**
     * Record the outcome of one unattended pass. The pass that matters is the
     * one that found work and could do NONE of it: [Fleet.Pass.considered] > 0
     * with [Fleet.Pass.acted] == 0 and no privileged channel is the exact shape
     * of a stranded device, and it is currently reported nowhere a user looks.
     */
    fun recordPass(ctx: Context, pass: Fleet.Pass) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stuck = pass.considered > 0 && pass.acted == 0 && !pass.silent
        p.edit()
            .putBoolean("pass_stuck", stuck)
            .putInt("pass_pending", pass.considered)
            .putString("pass_reason", pass.reason)
            .putLong("pass_at", System.currentTimeMillis())
            .apply()
        if (stuck) Log.w(TAG, "pass stuck: ${pass.considered} pending, ${pass.reason}")
    }

    // ── Trigger 3: the installed build is materially older than published ───

    /**
     * Record how old our OWN build is, decoded from its versionCode (see
     * [BuildAge]), when the store has confirmed a newer one is published.
     *
     * AGE, NOT GAP, on purpose. The exact published versionCode is not cheaply
     * available — the release sidecar carries a sha256 and the GHCR manifest a
     * digest, neither of which is a version — and reading it would mean
     * downloading the candidate just to ask how old it is. But the device
     * already knows when its own build was cut, because the versionCode IS a
     * timestamp; combined with "the store says something newer exists", that is
     * a complete and honest staleness signal with no extra request at all.
     *
     * [updateAvailable] false clears it: a device that is simply current must
     * not carry a stale warning about being stale.
     */
    fun recordSelfAge(ctx: Context, updateAvailable: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!updateAvailable) { p.edit().remove("self_gap_ms").apply(); return }
        val own = BootstrapInstall.installedVersionCode(ctx, ctx.packageName) ?: return
        val builtAt = BuildAge.builtAtMs(own) ?: return
        p.edit().putLong("self_gap_ms", System.currentTimeMillis() - builtAt).apply()
    }

    // ── Trigger 4: an out-of-band advisory published to the feed ────────────

    /**
     * Accept advisories fetched from the ntfy/RSS channel.
     *
     * THE ONLY CHANNEL THAT CAN SAY SOMETHING NEW. The banner and the
     * notification are built from the installed build's own strings and its own
     * logic, so a device stranded on an old build can only ever report what
     * that old build already knew how to report. The feed is server-side and
     * pull-based: an advisory published today reaches every device that still
     * polls, with no app update in between. For a fleet whose update chain is
     * the broken thing, that is the difference between reachable and not.
     *
     * The host fetches (network and feed parsing are its business, and the RSS
     * surface already exists); this stores, dedupes and merges them with the
     * locally-derived ones so all three surfaces render one list.
     *
     * See the FEED CONTRACT in [feedContract].
     */
    fun ingestExternal(ctx: Context, items: List<Item>) {
        val json = JSONArray()
        items.take(10).forEach { i ->
            json.put(JSONObject()
                .put("id", i.id).put("appId", i.appId ?: JSONObject.NULL)
                .put("title", i.title).put("detail", i.detail)
                .put("severity", i.severity.name).put("link", i.link ?: JSONObject.NULL)
                .put("publishedAtMs", i.publishedAtMs))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("feed_items", json.toString())
            .putLong("feed_at", System.currentTimeMillis())
            .apply()
        if (items.isNotEmpty()) Log.i(TAG, "ingested ${items.size} feed advisory item(s)")
        notifyListener()
    }

    /**
     * HOW TO POST A MESSAGE TO EVERY PHONE.
     *
     *     curl -d 'Server maintenance tonight 22:00' http://10.0.0.6:8090/fleet_advisory
     *
     * That is the whole thing. The text appears as the banner on every home
     * screen. Optionally give it a heading the way ntfy already does:
     *
     *     curl -H "Title: Maintenance" -d 'Back by 23:00.' http://10.0.0.6:8090/fleet_advisory
     *
     * Include a URL anywhere in the text and it becomes a button; an `.apk`
     * URL routes through the verified recovery installer rather than the
     * browser. No URL, no button.
     *
     * NEWEST POST WINS — this is a notice board, so it shows the current
     * notice, not a history. To take a message down, post the next one; to
     * clear the board entirely, post a single space, which is empty and shows
     * nothing.
     *
     * A message stops showing on its own once it is older than the staleness
     * bound (see [tooOld]), so a forgotten notice expires rather than living
     * for ntfy's full 30-day replay.
     *
     * The strict `{"kind":"fleet-advisory", …}` JSON form is still understood
     * and still supports `severity` and `retract` — the automated recovery
     * advisories use it. It is the special case; plain text is the norm.
     */
    const val feedContract: String =
        "plain text (newest wins); optional strict kind=fleet-advisory JSON"

    // ── Reading it back ─────────────────────────────────────────────────────

    /**
     * Everything worth telling the user right now, most severe first, minus
     * anything dismissed in this app session.
     */
    fun current(ctx: Context, fleet: List<Fleet.App> = emptyList()): List<Item> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = mutableListOf<Item>()

        // Feed first: its words are the only ones that can be newer than this build.
        runCatching {
            val arr = JSONArray(p.getString("feed_items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Item(
                    id = o.getString("id"),
                    appId = o.optString("appId").takeIf { it.isNotBlank() && it != "null" },
                    title = o.getString("title"),
                    detail = o.optString("detail"),
                    severity = runCatching { Severity.valueOf(o.optString("severity")) }
                        .getOrDefault(Severity.WARN),
                    source = "feed",
                    link = o.optString("link").takeIf { it.isNotBlank() && it != "null" },
                    publishedAtMs = o.optLong("publishedAtMs", 0L),
                )
            }
        }

        // Per-app repeated failures.
        fleet.forEach { app ->
            val n = p.getInt("fail_${app.id}", 0)
            if (n < FAILURE_THRESHOLD) return@forEach
            out += Item(
                id = "fail:${app.id}",
                appId = app.id,
                title = "${app.label} could not update ($n tries)",
                detail = (p.getString("why_${app.id}", "") ?: "") +
                    "\n\nTap to install it directly — this path needs no special " +
                    "access, only the standard Android install confirmation.",
                severity = Severity.STUCK,
                source = "local",
            )
        }

        // A whole pass that found work and could do none of it.
        if (p.getBoolean("pass_stuck", false)) {
            val pending = p.getInt("pass_pending", 0)
            out += Item(
                id = "pass:stuck",
                appId = null,
                title = "$pending update(s) cannot install",
                detail = (p.getString("pass_reason", "") ?: "") +
                    "\n\nDirect install still works: it uses the ordinary Android " +
                    "installer and needs no privileged access at all.",
                severity = Severity.STUCK,
                source = "local",
            )
        }

        // Our own build materially behind the published one.
        val gap = p.getLong("self_gap_ms", 0L)
        if (gap >= STALE_DAYS * 24L * 60L * 60L * 1000L) {
            out += Item(
                id = "stale:self",
                appId = null,
                title = "This app's build is ${BuildAge.humanDuration(gap)} old",
                detail = "A newer build has been published and this device has not " +
                    "taken it. Tap to install the current build directly.",
                severity = Severity.WARN,
                source = "local",
            )
        }

        return out.filter { !snoozed(p, it.id) && !tooOld(it) }
            .sortedByDescending { it.severity.ordinal }
    }

    /**
     * THE SECOND, INDEPENDENT BOUND — a banner must not be able to outlive its
     * usefulness even if every other mechanism fails.
     *
     * Polling with `since=all` means ntfy replays its whole 30-day cache on
     * every poll, so an advisory persists until someone explicitly retracts it.
     * That put the entire weight of "this eventually goes away" on two things
     * that can both fail: a publisher remembering to post a tombstone, and a
     * dismiss button that (see [dismiss]) did not work. When both failed the
     * result was a permanent banner, which is what actually happened.
     *
     * Server-side retention and a client-side staleness bound are not
     * alternatives — the honest design is both. An advisory about a build from
     * hours ago has no business on screen a fortnight later whatever the
     * server still holds, and a device that has been off for a month should
     * not wake up to a wall of history.
     *
     * Locally-derived advisories are exempt: they carry no publish time
     * because they are recomputed from live conditions every time [current] is
     * called, so they cannot be stale by construction — they vanish the moment
     * the condition does.
     */
    private fun tooOld(item: Item): Boolean {
        if (item.publishedAtMs <= 0L) return false
        val age = System.currentTimeMillis() - item.publishedAtMs
        return age > MAX_FEED_AGE_MS
    }

    /** Two weeks. Comfortably longer than any real incident takes to resolve,
     *  comfortably shorter than ntfy's 30-day replay window — so the client
     *  bound bites before the server one does, which is the point of having
     *  it. */
    private const val MAX_FEED_AGE_MS = 14L * 24L * 60L * 60L * 1000L

    /** Whether [item] may be raised as a NOTIFICATION now, marking it raised if
     *  so. The banner is not rate limited — it is passive and costs nothing to
     *  re-render; a notification is an interruption and must not repeat. */
    fun shouldNotify(ctx: Context, item: Item): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("shown_${item.id}", 0L)
        val now = System.currentTimeMillis()
        if (now - last < RENOTIFY_MS) return false
        p.edit().putLong("shown_${item.id}", now).apply()
        return true
    }

    // ── Session dismissal ───────────────────────────────────────────────────

    /**
     * "Later" means later. It used to mean nothing at all.
     *
     * This was an in-memory set, and the comment defending that choice argued a
     * permanently dismissible warning gets dismissed once and never seen again.
     * The reasoning was sound and the implementation did not implement it: THIS
     * APP IS THE LAUNCHER. Android restarts the launcher process routinely —
     * on memory pressure, on config change, many times an hour — and every
     * restart emptied the set. So the banner came back within minutes, every
     * time, forever, and the button did nothing a user could perceive.
     *
     * That is worse than having no button. A control that visibly does nothing
     * teaches the user that the whole surface is broken and not worth reading,
     * which is precisely the attention this advisory needs to keep for the one
     * time it matters.
     *
     * So it is persisted, and it is a SNOOZE rather than a permanent mute: the
     * original concern was right, and a device that still cannot update itself
     * a week later does need to say so again. [SNOOZE_MS] is the honest middle,
     * and the UI names the duration rather than saying a bare "Later".
     *
     * `commit()`, not `apply()`: the tap is very often followed by the user
     * leaving, and `apply()` is free to lose the write if the process dies
     * first — which would reproduce the exact bug this replaces.
     */
    private const val SNOOZE_MS = 7L * 24L * 60L * 60L * 1000L

    @SuppressLint("ApplySharedPref")
    fun dismiss(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("snooze_$id", System.currentTimeMillis())
            .commit()
        Log.i(TAG, "$id snoozed for ${SNOOZE_MS / 3_600_000L}h")
        notifyListener()
    }

    /** True while [id] is inside its snooze window. */
    private fun snoozed(p: android.content.SharedPreferences, id: String): Boolean =
        System.currentTimeMillis() - p.getLong("snooze_$id", 0L) < SNOOZE_MS

    // ── Change notification for the banner ──────────────────────────────────

    @Volatile private var listener: (() -> Unit)? = null
    fun setListener(l: (() -> Unit)?) { listener = l }
    private fun notifyListener() { runCatching { listener?.invoke() } }
}
