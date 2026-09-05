package com.diegonmarcos.superapp.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transport + offline queue for the user profile (name/email/phone/birth/…).
 *
 * WHY THIS EXISTS: the constellation update chain broke for thousands of
 * installs and there was NO out-of-band way to reach anyone. The profile is
 * the recovery channel — so it has to arrive at the server even when the app
 * itself is in a bad way, which is why this queues to disk instead of doing a
 * best-effort POST and shrugging.
 *
 * Engine only: no `R`, no Fragment, no prefs, no knowledge of what a "profile"
 * contains. The caller hands over an already-built JSON document; this object
 * owns delivery, retry and the failure taxonomy. That keeps the PII schema in
 * the app module and this file reusable.
 *
 * DELIBERATELY NOT [Telemetry]. The events ingest at /public/events/{app} is
 * the wrong home for this and reusing it would have been the easy mistake:
 *  • it is UNAUTHENTICATED — anyone could overwrite anyone's contact details;
 *  • it APPENDS to <app>-events.jsonl — "delete my data" becomes rewriting a
 *    log file, i.e. a deletion path that does not exist in practice;
 *  • it FANS OUT to ntfy — every field would land in push notifications on
 *    whatever devices subscribe to the topic.
 * Profiles therefore get their own record-oriented, authenticated route where
 * one install is one document and DELETE is an unlink. See the contract in
 * `docs/profile-sync-contract.md`.
 *
 * PII HYGIENE — this file must never log a profile field. Every log line here
 * carries counts, byte sizes, HTTP codes and the install id only; [redactBody]
 * scrubs server error bodies in case the endpoint echoes what we sent. The one
 * rule to preserve when editing: no `payload` substring ever reaches [Log].
 *
 * Diagnose with:  adb logcat -s ProfileSync
 */
object ProfileSyncClient {

    const val TAG = "ProfileSync"

    /** Where the not-yet-delivered document waits. One file, not a directory:
     *  a profile is full-state, so a newer edit fully supersedes an older one
     *  and a queue deeper than 1 would only replay stale contact details. */
    private const val QUEUE_DIR = "profile-sync"
    private const val QUEUE_FILE = "pending.json"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    /** Delivery outcome. [Retry] is the only one that keeps the queue file. */
    sealed class Result {
        /** 2xx — the server has it; queue cleared. */
        data class Delivered(val code: Int) : Result()
        /** Network down, timeout or 5xx — kept on disk for the next flush. */
        data class Retry(val reason: String) : Result()
        /** 4xx other than 429 — the request is wrong and replaying it forever
         *  would just be a silent hot loop, so the queue is cleared and the
         *  failure is surfaced instead. */
        data class Rejected(val code: Int, val reason: String) : Result()
    }

    private fun queueFile(context: Context): File =
        File(File(context.filesDir, QUEUE_DIR).apply { mkdirs() }, QUEUE_FILE)

    /** True when a document is waiting to go out — the profile screen shows
     *  this so a stuck sync is visible rather than silent. */
    @JvmStatic
    fun isPending(context: Context): Boolean = queueFile(context).isFile

    /**
     * Persist [payload] as the current pending document and try to deliver it
     * now on a background thread. Never blocks, never throws into the caller.
     *
     * Writing BEFORE the attempt is the point: if the process dies mid-POST,
     * or the radio is off, the document is already durable and the next
     * [flush] picks it up.
     */
    @JvmStatic
    fun enqueue(context: Context, endpoint: String, installId: String, secret: String, payload: JSONObject) {
        val ctx = context.applicationContext
        val body = payload.toString()
        val written = runCatching { queueFile(ctx).writeText(body) }.isSuccess
        if (!written) {
            // Nothing is durable, so there is nothing for a later flush to pick
            // up. Say so instead of starting a thread that would post nothing.
            Log.w(TAG, "queue write failed — profile not persisted, not sending")
            return
        }
        Log.i(TAG, "queued profile document (${body.length} bytes) for $installId")
        Thread({ flushBlocking(ctx, endpoint, installId, secret) }, "profile-sync-enqueue").start()
    }

    /**
     * Retry whatever is queued. Cheap no-op when nothing is pending, so it is
     * safe to call from Application.onCreate and on every profile-screen
     * resume — those are the two moments connectivity has plausibly returned.
     */
    @JvmStatic
    fun flush(context: Context, endpoint: String, installId: String, secret: String) {
        val ctx = context.applicationContext
        if (!isPending(ctx)) return
        Thread({ flushBlocking(ctx, endpoint, installId, secret) }, "profile-sync-flush").start()
    }

    /** Blocking delivery of the queued document. Call off the main thread. */
    private fun flushBlocking(context: Context, endpoint: String, installId: String, secret: String): Result {
        val file = queueFile(context)
        val body = runCatching { file.readText() }.getOrNull()
            ?: return Result.Retry("queue unreadable")
        val result = send(endpoint, "POST", installId, secret, body)
        when (result) {
            is Result.Delivered -> {
                file.delete()
                Log.i(TAG, "profile delivered (HTTP ${result.code}), queue cleared")
            }
            is Result.Rejected -> {
                file.delete()
                Log.w(TAG, "profile rejected (HTTP ${result.code}): ${result.reason} — queue cleared, not retrying")
            }
            is Result.Retry ->
                Log.w(TAG, "profile delivery deferred: ${result.reason} — still queued")
        }
        return result
    }

    /**
     * GET the server's copy, for restore-on-reinstall. Blocking; returns null
     * when there is nothing to restore or the fetch failed (both are ordinary
     * outcomes on a fresh install, so neither is an error).
     */
    @JvmStatic
    fun fetch(endpoint: String, installId: String, secret: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(endpoint, "GET", installId, secret)
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.i(TAG, "no profile to restore (HTTP $code)")
                return null
            }
            val text = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(TAG, "restored profile document (${text.length} bytes)")
            JSONObject(text)
        } catch (t: Throwable) {
            Log.w(TAG, "profile fetch failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Ask the server to erase this install's record (GDPR erasure). Also drops
     * any queued document, so a delete cannot be undone by a pending write
     * that was still sitting on disk — that ordering bug is exactly how a
     * "deleted" profile comes back.
     */
    @JvmStatic
    fun delete(context: Context, endpoint: String, installId: String, secret: String, onResult: (Result) -> Unit = {}) {
        val ctx = context.applicationContext
        queueFile(ctx).delete()
        Thread({
            val result = send(endpoint, "DELETE", installId, secret, body = null)
            Log.i(TAG, "profile erasure request -> $result")
            onResult(result)
        }, "profile-sync-delete").start()
    }

    /** One request, one failure taxonomy. [body] null for GET/DELETE. */
    private fun send(endpoint: String, method: String, installId: String, secret: String, body: String?): Result {
        if (endpoint.isBlank()) return Result.Rejected(0, "no profile sync endpoint configured")
        // Checked here, not only in open(), so a misconfigured http:// endpoint
        // is Rejected (queue cleared, reported) rather than Retry'd forever by
        // the catch below — a permanent config error must not become a silent
        // background loop.
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            return Result.Rejected(0, "endpoint is not https — refusing to send personal data in clear text")
        }
        var conn: HttpURLConnection? = null
        try {
            conn = open(endpoint, method, installId, secret)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            return when {
                code in 200..299 -> Result.Delivered(code)
                // 429 is explicitly retryable — the server is asking us to slow
                // down, not telling us the document is bad.
                code == 429 || code >= 500 -> Result.Retry("HTTP $code")
                else -> {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Result.Rejected(code, redactBody(err))
                }
            }
        } catch (t: Throwable) {
            // UnknownHost / SocketTimeout / SSL / Connect all mean "never
            // arrived", which is precisely the case the queue exists for.
            return Result.Retry("${t.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * TLS-only by contract. An `http://` endpoint would put names, emails and
     * phone numbers on the wire in clear text, so it is refused outright
     * rather than downgraded silently.
     *
     * The install id travels as a HEADER, never in the path or query string:
     * URLs land in proxy and web-server access logs, and a per-person
     * identifier sitting in those logs is a data-retention problem that is
     * invisible until someone asks for a deletion.
     */
    private fun open(endpoint: String, method: String, installId: String, secret: String): HttpURLConnection {
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "profile sync endpoint must be https (refusing to send personal data in clear text)"
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // A redirect off-host would replay the Authorization header and the
            // profile body at whatever the gateway points to. Never follow.
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Cloud-SuperApp-ProfileSync/1")
            setRequestProperty("X-Install-Id", installId)
            setRequestProperty("Authorization", "Bearer $secret")
        }
    }

    /** Server error bodies are echoed into logs; a 4xx that quotes the offending
     *  field would otherwise put PII in logcat. Keep it short and structural. */
    private fun redactBody(text: String): String =
        text.take(200).replace(Regex("[\\w.+-]+@[\\w.-]+"), "«email»")
            .replace(Regex("\\+?\\d[\\d ()-]{6,}"), "«number»")
}
