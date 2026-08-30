package com.diegonmarcos.superapp.devtools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud-constellation diagnostics sink. Reuses DevControlServer's on-device
 * logcat/trace/crashes capture and does two things with the bundle:
 *
 *   1. downloadBundle() — write it to the public Downloads dir (user keeps a
 *      copy / attaches it anywhere). No network, no permission on API 29+.
 *   2. pushToCloud()    — POST it to our OpenObserve (the Loki-equivalent log
 *      store) via the DATA-DRIVEN endpoint baked from build.json::diagnostics
 *      (LOG_SINK_URL + LOG_SINK_STREAM). No credential is embedded — the URL is
 *      the public first-party collector that relays to OpenObserve server-side.
 *
 * buildRecord() is pure (no Android) so it is unit-tested on the JVM.
 */
object DiagnosticsPush {

    /** OpenObserve `_json` ingest accepts an array of flat JSON records. We emit
     *  ONE record carrying the whole debug bundle + identity fields. Pure. */
    fun buildRecord(
        appId: String,
        versionName: String,
        versionCode: String,
        gitSha: String,
        device: String,
        androidRelease: String,
        sdkInt: Int,
        tsIso: String,
        logcat: String,
        trace: String,
        crashes: String,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder()
        sb.append("[{")
        fun field(k: String, v: String, last: Boolean = false) {
            sb.append('"').append(k).append("\":\"").append(esc(v)).append('"')
            if (!last) sb.append(',')
        }
        field("_timestamp", tsIso)
        field("app", appId)
        field("version_name", versionName)
        field("version_code", versionCode)
        field("git_sha", gitSha)
        field("device", device)
        field("android_release", androidRelease)
        field("sdk_int", sdkInt.toString())
        for ((k, v) in extra) field("x_$k", v)
        field("logcat", logcat)
        field("trace", trace)
        field("crashes", crashes, last = true)
        sb.append("}]")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")

    /** POST the record to the data-driven OpenObserve sink. Returns HTTP status
     *  or a negative code on transport error. No-op (returns -2) when the sink
     *  URL is empty (feature disabled in build.json). */
    fun pushToCloud(body: String): Int {
        val base = BuildConfig.LOG_SINK_URL
        if (base.isBlank()) return -2
        val url = if (base.contains("{stream}"))
            base.replace("{stream}", BuildConfig.LOG_SINK_STREAM) else base
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.disconnect()
            code
        }.getOrElse { -1 }
    }

    /** Write the bundle to public Downloads. Returns the display name on
     *  success, null on failure. */
    /** True when a report can be sent at all — drives whether the Send button
     *  is offered. Empty URL is a legitimate configuration (an app that does
     *  not want to phone home), not an error. */
    /** A place a report can be POSTed. [url] may contain {app}. */
    data class ReportTarget(val id: String, val label: String, val url: String)

    /** Declared destinations, in build.json order. Empty list = Send is hidden. */
    fun reportTargets(): List<ReportTarget> = runCatching {
        val arr = org.json.JSONArray(
            String(android.util.Base64.decode(BuildConfig.DIAG_TARGETS_B64, android.util.Base64.NO_WRAP)))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ReportTarget(o.optString("id"), o.optString("label"), o.optString("url"))
        }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    /** POST to one named target; same contract as [postReport]. */
    fun postReportTo(target: ReportTarget, app: String, body: String): Int {
        val url = if (target.url.contains("{app}")) target.url.replace("{app}", app)
                  else "${target.url.trimEnd('/')}/$app"
        return postToUrl(url, body)
    }

    fun canReport(): Boolean = BuildConfig.DIAG_REPORT_URL.isNotBlank()

    /**
     * POST a human-triggered diagnostic report for [app] and return the HTTP
     * status, or -1 when it could not be sent at all.
     *
     * Separate from [pushToCloud]: that is a fire-and-forget analytics record
     * on a fixed stream, this is a bundle someone is standing there waiting
     * for. Plain text, not JSON — it is read by a person first and a tool
     * second, and wrapping it in JSON only adds escaping to something that is
     * already a log.
     */
    fun postReport(app: String, body: String): Int {
        val base = BuildConfig.DIAG_REPORT_URL
        if (base.isBlank()) return -1
        val url = if (base.contains("{app}")) base.replace("{app}", app) else "$base/$app"
        return postToUrl(url, body)
    }

    private fun postToUrl(url: String, body: String): Int {
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.disconnect()
            code
        }.getOrDefault(-1)
    }

    fun downloadBundle(ctx: Context, filename: String, body: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            filename
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, filename).apply { writeText(body) }
            filename
        }
    }.getOrNull()
}
