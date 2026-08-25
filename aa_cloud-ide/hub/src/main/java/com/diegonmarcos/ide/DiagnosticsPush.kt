package com.diegonmarcos.ide

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-contained diagnostics for the thin hub (no DevControlServer here).
 * Captures the app's own logcat + device identity into one OpenObserve record,
 * then either writes it to public Downloads (downloadBundle) or POSTs it to our
 * OpenObserve / Loki-equivalent log store (pushToCloud) via the DATA-DRIVEN sink
 * baked from build.json::diagnostics (BuildConfig.LOG_SINK_URL / LOG_SINK_STREAM).
 * No credential is embedded — the URL is the public collector that relays to
 * openobserve server-side. buildRecord() is pure → JVM-unit-tested.
 */
object DiagnosticsPush {

    fun buildRecord(
        appId: String, versionName: String, versionCode: String, gitSha: String,
        device: String, androidRelease: String, sdkInt: Int, tsIso: String,
        logcat: String, crashes: String, extra: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder("[{")
        fun field(k: String, v: String, last: Boolean = false) {
            sb.append('"').append(k).append("\":\"").append(esc(v)).append('"')
            if (!last) sb.append(',')
        }
        field("_timestamp", tsIso); field("app", appId)
        field("version_name", versionName); field("version_code", versionCode)
        field("git_sha", gitSha); field("device", device)
        field("android_release", androidRelease); field("sdk_int", sdkInt.toString())
        for ((k, v) in extra) field("x_$k", v)
        field("logcat", logcat); field("crashes", crashes, last = true)
        sb.append("}]")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")

    /** Capture the app's OWN logcat (uid-filtered, no permission needed since
     *  4.1) + device info into the OpenObserve record. */
    fun captureRecord(ctx: Context): String {
        val logcat = runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "-v", "threadtime"))
                .inputStream.bufferedReader().readText()
        }.getOrElse { "logcat read failed: $it\n" }
        val crashes = runCatching {
            val dir = java.io.File(ctx.getExternalFilesDir(null), "crashes")
            dir.listFiles()?.sortedByDescending { it.lastModified() }
                ?.joinToString("\n\n") { "[${it.name}]\n" + it.readText() } ?: ""
        }.getOrElse { "" }
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        return buildRecord(
            appId = BuildConfig.APPLICATION_ID, versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toString(), gitSha = BuildConfig.GIT_SHORT_SHA,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT, tsIso = ts, logcat = logcat, crashes = crashes,
        )
    }

    /** POST to the data-driven sink. HTTP status, or -1 transport error, -2 disabled (empty url). */
    fun pushToCloud(body: String): Int {
        val base = BuildConfig.LOG_SINK_URL
        if (base.isBlank()) return -2
        val url = if (base.contains("{stream}")) base.replace("{stream}", BuildConfig.LOG_SINK_STREAM) else base
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode; c.disconnect(); code
        }.getOrElse { -1 }
    }

    /** Write the bundle to public Downloads. Returns the display name or null. */
    fun downloadBundle(ctx: Context, filename: String, body: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            filename
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, filename).apply { writeText(body) }; filename
        }
    }.getOrNull()
}
