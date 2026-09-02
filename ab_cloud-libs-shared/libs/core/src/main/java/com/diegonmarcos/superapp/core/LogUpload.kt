package com.diegonmarcos.superapp.core

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * POST a log dump to c3-infra-api's public ingest, so a broken phone can hand
 * over its own evidence instead of the user copy-pasting a stack trace.
 *
 * (Written `/public/…` and not with a star: Kotlin block comments NEST, so a
 *  literal /*-sequence inside this KDoc opens an inner comment, the closing
 *  */ shuts only that one, and the outer comment then swallows the rest of
 *  the file — the compiler reports it as an unclosed comment at EOF.)
 *
 * WHY UNAUTHENTICATED IS RIGHT HERE
 * `/public/…` is on the API's isPublicPath() allowlist deliberately: the
 * moments you most need a log are the ones where auth is what broke. Requiring
 * a bearer would make the reporting path fail for exactly the class of bug it
 * exists to report — the same trap that hid today's outages behind silent
 * catch blocks.
 *
 * WHAT THE SERVER DOES (routes/publicLogs.ts): writes <source>-<epoch>.log
 * under PUBLIC_LOGS_DIR, caps the body at 2 MB, and sanitises `source` to
 * [A-Za-z0-9_-]. So the caller's only jobs are: stay under the cap, and do not
 * ship secrets.
 */
object LogUpload {
    private const val TAG = "LogUpload"

    /** Server cap is 2 MB; leave room for JSON escaping of the payload. */
    private const val MAX_BYTES = 1_500_000

    /** Lines carrying credentials never leave the device. A log dump is the
     *  single likeliest place to leak one: Authorization headers, bearer
     *  tokens, WireGuard keys and passwords all show up in stack traces and
     *  request logs. Redacting the VALUE (not dropping the line) keeps the
     *  surrounding context readable, which is the reason to send a log. */
    private val SECRET = Regex(
        """(?i)\b(authorization|bearer|token|password|passwd|secret|privatekey|private_key|presharedkey|api[_-]?key)\b(\s*[:=]\s*)\S+""",
    )

    /** Keeps the key and the separator, replaces only the value — group 1 is
     *  the label, group 2 the separator, so the line still reads as itself. */
    fun redact(raw: String): String =
        SECRET.replace(raw) { m -> m.groupValues[1] + m.groupValues[2] + "<redacted>" }

    /**
     * Capture this process's own logcat (own-process only — that is all an
     * unprivileged uid may read), redact it, and cap it to [maxBytes] newest
     * bytes so a chatty app never blows the server's 2 MB body limit.
     *
     * Shared by [Telemetry.postLogcat] so the dump+redact+cap logic exists in
     * exactly one place instead of being copy-pasted per caller.
     */
    internal fun captureAndRedactLogcat(maxBytes: Int = MAX_BYTES): String {
        val raw = runCatching {
            val proc = ProcessBuilder("logcat", "-d", "-v", "time")
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().use(BufferedReader::readText).also { proc.destroy() }
        }.getOrElse { "logcat capture failed: ${it.javaClass.simpleName}: ${it.message}" }
        val redacted = redact(raw)
        return if (redacted.length > maxBytes) redacted.takeLast(maxBytes) else redacted
    }

    /** Result of an upload attempt — named, never a bare boolean, so the UI can
     *  tell the user WHICH step failed rather than "upload failed". */
    sealed class Result {
        data class Ok(val file: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Blocking — call from a background thread.
     *
     * [endpoint] is the full ingest URL (data-driven from build.json, never a
     * literal here), [source] names the app in the stored filename.
     */
    fun post(endpoint: String, source: String, log: String): Result {
        if (endpoint.isBlank()) return Result.Failed("no log endpoint configured")
        val body = redact(log).let { if (it.length > MAX_BYTES) it.takeLast(MAX_BYTES) else it }
        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // The ingest is public by design; sending no Authorization is
                // intentional, not an oversight.
            }
            val payload = JSONObject()
                .put("source", source)
                .put("log", body)
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reply = stream?.let { BufferedReader(InputStreamReader(it)).use(BufferedReader::readText) }.orEmpty()
            conn.disconnect()
            Log.i(TAG, "POST $endpoint -> $code (${body.length} bytes)")
            when {
                code in 200..299 -> Result.Ok(
                    runCatching { JSONObject(reply).optString("file") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "(stored)",
                )
                code == 413 -> Result.Failed("HTTP 413 — log too large for the server")
                code == 400 -> Result.Failed("HTTP 400 — server rejected the body")
                else -> Result.Failed("HTTP $code${reply.take(120).let { if (it.isBlank()) "" else " — $it" }}")
            }
        } catch (t: Throwable) {
            // Name the failure. "Upload failed" with no cause is how today's
            // bugs stayed invisible for weeks.
            Result.Failed("${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
        }
    }
}
