package com.diegonmarcos.superapp.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Constellation-wide telemetry DEFAULT — every app that links libs:core gets
 * this for free, with zero per-app wiring. POSTs to c3-infra-api's public
 * events ingest:
 *
 *   POST https://api.diegonmarcos.com/c3-infra-api/public/events/{app}
 *   { kind: "log"|"debug"|"action"|"probe"|"crash", title?, message?, log?, meta? }
 *
 * 2 MB cap, unauthenticated (same "public path" reasoning as [LogUpload] —
 * the moments you most need telemetry are the ones where auth broke), and
 * the server fans a summary out to ntfy topic infra-{app}.
 *
 * WHY A DERIVED ENDPOINT, UNLIKE [LogUpload]: LogUpload's endpoint is a bare
 * parameter the caller must supply (superapp reads its own
 * BuildConfig.LOG_INGEST_URL, empty unless wired), so most apps silently ship
 * no telemetry at all. Telemetry instead derives a sensible default from the
 * app's own package name — "add libs:core" is enough on its own. An app opts
 * OUT by overriding TELEMETRY_INGEST_URL to "", not by doing nothing.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private const val BASE_URL = "https://api.diegonmarcos.com/c3-infra-api/public/events/"

    /**
     * Derive the app id the events ingest and the ntfy topic key on:
     * last segment(s) of the package name after "com.diegonmarcos.", with
     * any remaining dots turned into '-'.
     *
     *   com.diegonmarcos.comms.mail -> "comms-mail"
     *
     * A package outside that prefix (should not happen in this constellation,
     * but must never throw) falls back to the whole package name, dots
     * turned into '-'.
     */
    @JvmStatic
    fun appId(context: Context): String {
        val pkg = context.packageName
        val prefix = "com.diegonmarcos."
        val tail = if (pkg.startsWith(prefix)) pkg.removePrefix(prefix) else pkg
        return tail.replace('.', '-')
    }

    /**
     * An app opts into a non-default endpoint (or opts out with "") by
     * declaring `buildConfigField "String", "TELEMETRY_INGEST_URL", "\"…\""`
     * in its OWN app/build.gradle. libs:core has no compile-time visibility
     * into the consuming app's generated BuildConfig class — the same reason
     * LogUpload's caller reads BuildConfig.LOG_INGEST_URL from its own app
     * module rather than from inside LogUpload — so this reads it reflectively
     * instead. Missing class or missing field (including apps like
     * ac_cloud-mail, whose namespace `eu.faircode.email` differs from its
     * applicationId/packageName, so no `<packageName>.BuildConfig` class
     * exists at all) is not an error: it just means the app gets the derived
     * default, which is the whole point.
     */
    private fun overrideEndpoint(context: Context): String? = runCatching {
        val cls = Class.forName("${context.packageName}.BuildConfig")
        val field = cls.getField("TELEMETRY_INGEST_URL")
        (field.get(null) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Full ingest URL this app will post to. */
    @JvmStatic
    fun endpoint(context: Context): String =
        overrideEndpoint(context) ?: (BASE_URL + appId(context))

    /**
     * Fire-and-forget: runs on its own background thread, never throws into
     * the caller, never blocks it. [kind] is one of "log" | "debug" |
     * "action" | "probe" | "crash" — the server does not validate it, so an
     * unrecognized value is still sent, not rejected client-side.
     *
     * [log] is not part of the primary signature apps call (title/message
     * cover the common case) but is exposed as a trailing optional so
     * [postLogcat] and [installCrashHandler] can reuse this same code path.
     */
    @JvmStatic
    @JvmOverloads
    fun post(
        context: Context,
        kind: String,
        title: String? = null,
        message: String? = null,
        meta: Map<String, String> = emptyMap(),
        log: String? = null,
    ) {
        val ctx = context.applicationContext
        Thread({
            runCatching { postBlocking(ctx, kind, title, message, log, meta) }
                .onFailure { Log.w(TAG, "post failed: ${it.javaClass.simpleName}: ${it.message}") }
        }, "telemetry-post").start()
    }

    /**
     * Convenience: capture this process's own logcat (redacted, capped —
     * reuses [LogUpload.captureAndRedactLogcat]) and post it as `log`.
     */
    @JvmStatic
    @JvmOverloads
    fun postLogcat(context: Context, kind: String = "log", title: String? = null) {
        val ctx = context.applicationContext
        Thread({
            runCatching {
                val log = LogUpload.captureAndRedactLogcat()
                postBlocking(ctx, kind, title, message = null, log = log, meta = emptyMap())
            }.onFailure { Log.w(TAG, "postLogcat failed: ${it.javaClass.simpleName}: ${it.message}") }
        }, "telemetry-postlogcat").start()
    }

    /** Guards [installCrashHandler] against being wired twice (e.g. a second
     *  Application.onCreate on process restart within the same JVM instance,
     *  or a careless duplicate call) — a second install would chain the first
     *  install's handler as "previous", not the OS default. */
    private val crashHandlerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Default crash telemetry: wraps the current
     * Thread.defaultUncaughtExceptionHandler, POSTs kind="crash" with the
     * redacted stack trace as `log`, then always delegates to the previous
     * handler (so crash reporting never suppresses the system crash dialog,
     * process-death cleanup, or another crash reporter already installed).
     *
     * Posts BLOCKING, not fire-and-forget: the process is about to die, so a
     * background thread's own POST would very likely never get to run.
     * Connect/read timeouts keep a dead network from hanging the crash for
     * more than a couple of seconds.
     */
    @JvmStatic
    fun installCrashHandler(context: Context) {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                postBlocking(
                    ctx,
                    kind = "crash",
                    title = throwable.javaClass.name,
                    message = throwable.message,
                    log = LogUpload.redact(sw.toString()),
                    meta = emptyMap(),
                )
            } catch (t: Throwable) {
                // Telemetry must never be the reason a crash goes unreported
                // upstream — swallow and fall through to the previous handler.
                Log.w(TAG, "crash telemetry failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                previous?.uncaughtException(thread, throwable)
                    ?: run {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
            }
        }
    }

    private fun postBlocking(
        context: Context,
        kind: String,
        title: String?,
        message: String?,
        log: String?,
        meta: Map<String, String>,
    ) {
        val url = endpoint(context)
        if (url.isBlank()) {
            Log.i(TAG, "telemetry disabled (blank endpoint)")
            return
        }
        val payload = JSONObject().apply {
            put("kind", kind)
            title?.let { put("title", it) }
            message?.let { put("message", it) }
            log?.let { put("log", it) }
            if (meta.isNotEmpty()) put("meta", JSONObject(meta))
        }.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Public endpoint by design — no Authorization, same reasoning as
            // LogUpload.
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        Log.i(TAG, "POST $url -> $code (kind=$kind, ${payload.length} bytes)")
    }
}
