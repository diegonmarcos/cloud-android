package com.diegonmarcos.superapp.devcontrol

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback HTTP/1.1 control surface for the app — same role Termux:API
 * plays for the OS, but reachable AND functional from this device's
 * shell (no signature gating because we bind to 127.0.0.1).
 *
 * Endpoints (Bearer token required unless marked NO-AUTH):
 *
 *   GET  /ping                    → pong                       [NO-AUTH]
 *   GET  /info                    → {version, vc, port}        [NO-AUTH]
 *   GET  /state                   → {section, mode, …}
 *   POST /haptic?preset=X         → fire haptic preset
 *                                    (gemini_stream, tick, start, end)
 *   POST /goto?target=URI         → onTileClicked(target)
 *                                    section: / page: / action: / http(s):
 *                                    / intent: / app: / stub:
 *   POST /action?type=X           → dispatchHomeAction(X)
 *   POST /update                  → equivalent to action=check_updates
 *   POST /restart                 → kill+relaunch the app process
 *
 * The server runs on a single accept-loop thread; each connection is
 * handled inline (response is short — no need for a thread pool).
 * Workload that touches UI is posted onto the main Looper via
 * [DevControlBridge].
 */
object DevControlServer {

    private const val TAG = "DevControl"
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = DevControlPrefs(app)
        if (!prefs.enabled) return
        if (!running.compareAndSet(false, true)) return
        val port = prefs.port
        val token = prefs.token
        thread = Thread({ runServer(app, port, token) }, "DevControl-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        thread?.interrupt()
        thread = null
    }

    /** True if the accept loop is currently listening. */
    fun isRunning(): Boolean = running.get()

    /**
     * Returns the socket's actual bind address (e.g. "127.0.0.1") so
     * the About page can verify it really is loopback-only.
     * Returns null if not running.
     */
    fun boundHost(): String? {
        val addr = server?.inetAddress ?: return null
        return addr.hostAddress
    }

    /** True iff the listener is bound to a loopback address (127.x.x.x
     *  or ::1). False positives are impossible — boundHost is read from
     *  the actual ServerSocket. */
    fun isLoopbackOnly(): Boolean {
        val addr = server?.inetAddress ?: return false
        return addr.isLoopbackAddress
    }

    private fun runServer(ctx: Context, port: Int, token: String) {
        try {
            server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "listening on 127.0.0.1:$port")
            while (running.get()) {
                val s = server?.accept() ?: break
                runCatching { handle(ctx, s, token) }.onFailure { Log.w(TAG, "handle: $it") }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "server died: $t")
        } finally {
            runCatching { server?.close() }
            running.set(false)
        }
    }

    private fun handle(ctx: Context, socket: Socket, token: String) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), false)

            val first = reader.readLine() ?: return
            val parts = first.split(" ")
            if (parts.size < 3) { reply(writer, "400 Bad Request", "bad request\n"); return }
            val method = parts[0]
            val pathAndQuery = parts[1]
            val path = pathAndQuery.substringBefore('?')
            val query = parseQuery(pathAndQuery.substringAfter('?', ""))

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val auth = headers["authorization"]
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim().orEmpty()
            val authed = auth == token

            // Normalise both legacy flat paths AND the new /v1/{group}/{op}
            // layout to a single canonical op name. The op name is what
            // the routing + the /v1/docs catalog below both key on, so
            // there's exactly ONE source of truth for which endpoints
            // exist.
            val op = canonicalOp(path)

            // ENDPOINT CATALOG — declared once, used by both the
            // routing switch AND /v1/docs. Adding an endpoint = one
            // entry here + one branch in the switch below. /v1/docs
            // updates automatically.
            //
            // auth = false for diagnostic endpoints that are SAFE to
            // expose unauth'd because (a) they only emit data ABOUT
            // this app, and (b) the server binds on 127.0.0.1 so
            // anything reaching it already owns the device shell.

            when (op) {
                "docs" -> {
                    reply(writer, "200 OK", endpointDocsJson(DevControlPrefs(ctx).port), "application/json")
                    return
                }
                "system/ping" -> { reply(writer, "200 OK", "pong\n"); return }
                "system/info" -> {
                    val body = """{"version":"${BuildConfig.VERSION_NAME}","vc":${BuildConfig.VERSION_CODE},"sha":"${BuildConfig.GIT_SHORT_SHA}","port":${DevControlPrefs(ctx).port}}"""
                    reply(writer, "200 OK", body, "application/json")
                    return
                }
                "diagnostics/logcat" -> { reply(writer, "200 OK", readLogcat(query["n"]?.toIntOrNull() ?: 300)); return }
                "diagnostics/trace"  -> { reply(writer, "200 OK", readTraceTail(ctx, query["n"]?.toIntOrNull() ?: 300)); return }
                "diagnostics/crashes" -> { reply(writer, "200 OK", readCrashes(ctx)); return }
            }

            if (!authed) { reply(writer, "401 Unauthorized", "unauthorized\n"); return }

            // Authenticated endpoints
            when (op) {
                "state" -> {
                    val snap = DevControlBridge.host()?.stateSnapshot() ?: emptyMap()
                    val body = snap.entries.joinToString(",", "{", "}") {
                        "\"${jsonEscape(it.key)}\":\"${jsonEscape(it.value)}\""
                    }
                    reply(writer, "200 OK", body, "application/json")
                }
                "haptic" -> {
                    val preset = query["preset"] ?: "tick"
                    DevControlBridge.runOnMain {
                        DevControlBridge.host()?.firePresetHaptic(preset)
                    }
                    reply(writer, "200 OK", """{"ok":true,"preset":"${jsonEscape(preset)}"}""", "application/json")
                }
                "nav/goto" -> {
                    val target = query["target"]
                    if (target.isNullOrBlank()) {
                        reply(writer, "400 Bad Request", "missing target\n")
                    } else {
                        DevControlBridge.runOnMain {
                            DevControlBridge.host()?.onTileFromServer(target)
                        }
                        reply(writer, "200 OK", "ok\n")
                    }
                }
                "nav/action" -> {
                    val type = query["type"]
                    if (type.isNullOrBlank()) {
                        reply(writer, "400 Bad Request", "missing type\n")
                    } else {
                        DevControlBridge.runOnMain {
                            DevControlBridge.host()?.onActionFromServer(type)
                        }
                        reply(writer, "200 OK", "ok\n")
                    }
                }
                "system/update" -> {
                    DevControlBridge.runOnMain {
                        DevControlBridge.host()?.onActionFromServer("check_updates")
                    }
                    reply(writer, "200 OK", "update queued\n")
                }
                "system/restart" -> {
                    reply(writer, "200 OK", "restarting…\n")
                    writer.flush()
                    DevControlBridge.runOnMain { DevControlBridge.restartApp(ctx) }
                }
                "tracker/prefs"  -> { reply(writer, "200 OK", trackerPrefsJson(ctx), "application/json") }
                "tracker/points" -> {
                    val n = query["n"]?.toIntOrNull() ?: 20
                    reply(writer, "200 OK", trackerPointsJson(ctx, n), "application/json")
                }
                "tracker/stops"  -> { reply(writer, "200 OK", trackerStopsJson(ctx), "application/json") }
                "tracker/counts" -> { reply(writer, "200 OK", trackerCountsJson(ctx), "application/json") }
                else -> reply(writer, "404 Not Found", "not found — see /v1/docs\n")
            }
        }
    }

    /** Map both `/ping` (legacy flat) and `/v1/system/ping` (new) to
     *  the canonical op name `system/ping`. The routing + the /v1/docs
     *  catalog both key on this canonical name. New endpoints SHOULD
     *  be added under the v1/{group}/{op} form only; legacy aliases
     *  are kept so existing curls in the user's Configs/About panel
     *  don't break. */
    private fun canonicalOp(path: String): String {
        // Strip /v1/ prefix if present.
        val stripped = path.removePrefix("/v1/").removePrefix("/")
        // Legacy flat → canonical group/op mapping. Keep in sync with
        // the docs catalog below.
        return when (stripped) {
            "ping"     -> "system/ping"
            "info"     -> "system/info"
            "logcat"   -> "diagnostics/logcat"
            "trace"    -> "diagnostics/trace"
            "crashes"  -> "diagnostics/crashes"
            "goto"     -> "nav/goto"
            "action"   -> "nav/action"
            "update"   -> "system/update"
            "restart"  -> "system/restart"
            else       -> stripped
        }
    }

    /** Returns the API docs as JSON — single source of truth lives
     *  here, mirrors exactly what the routing switch handles. */
    private fun endpointDocsJson(port: Int): String {
        // Each entry: [op, methods, auth, description, paramsCSV]
        val rows = listOf(
            Spec("docs",                "GET",  false, "This endpoint — JSON catalog of every route, method, and auth requirement", ""),
            Spec("system/ping",         "GET",  false, "Health probe — returns 'pong'", ""),
            Spec("system/info",         "GET",  false, "App build info: version, vc, sha, port", ""),
            Spec("system/update",       "POST", true,  "Trigger an in-app update check (libs:updater)", ""),
            Spec("system/restart",      "POST", true,  "Restart the SuperApp process", ""),
            Spec("diagnostics/logcat",  "GET",  false, "Recent logcat lines, threadtime format", "n=lines (default 300)"),
            Spec("diagnostics/trace",   "GET",  false, "Tail of Trace.kt's trace.log", "n=lines (default 300)"),
            Spec("diagnostics/crashes", "GET",  false, "All crash files concatenated, newest first", ""),
            Spec("state",               "GET",  true,  "Snapshot of MainActivity's live state map (section, label, mode, …)", ""),
            Spec("haptic",              "POST", true,  "Fire a named haptic preset on the device", "preset=name (default 'tick')"),
            Spec("nav/goto",            "POST", true,  "Navigate to a tile target (section:X / page:X/Y / action:X / url)", "target=string"),
            Spec("nav/action",          "POST", true,  "Fire one of MainActivity.onActionFromServer's verbs", "type=string"),
            Spec("tracker/prefs",       "GET",  true,  "Tracker calibration prefs + enabled flag + last fix coords", ""),
            Spec("tracker/counts",      "GET",  true,  "DB row counts: points + stops + last-fix timestamp", ""),
            Spec("tracker/points",      "GET",  true,  "Recent GPS points reverse-chrono with accuracy + speed", "n=count (default 20)"),
            Spec("tracker/stops",       "GET",  true,  "All stops in the local DB with start/end + reverse-geocoded place", ""),
        )
        val sb = StringBuilder()
        sb.append("""{"port":""").append(port).append(',')
        sb.append(""""base":"http://127.0.0.1:""").append(port).append("\",")
        sb.append(""""auth":"Bearer <token> in Authorization header (token visible in Configs/About → Dev control HTTP)",""")
        sb.append(""""path_styles":["/v1/{group}/{op} (preferred)","/{op} (legacy flat aliases — same handler)"],""")
        sb.append(""""endpoints":[""")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append(""""op":"""").append(jsonEscape(r.op)).append("\",")
            sb.append(""""path":"/v1/""").append(jsonEscape(r.op)).append("\",")
            sb.append(""""method":"""").append(jsonEscape(r.method)).append("\",")
            sb.append(""""auth":""").append(r.auth).append(',')
            sb.append(""""description":"""").append(jsonEscape(r.description)).append("\",")
            sb.append(""""params":"""").append(jsonEscape(r.params)).append('"')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private data class Spec(
        val op: String,
        val method: String,
        val auth: Boolean,
        val description: String,
        val params: String,
    )

    // ── Tracker DB read helpers (libs:maps consumers) ───────────────

    private fun trackerPrefsJson(ctx: Context): String {
        val tp = com.diegonmarcos.superapp.maps.MapsTrackingPrefs(ctx)
        val st = com.diegonmarcos.superapp.maps.MapsTrackerPrefs(ctx)
        return """{"enabled":${st.enabled},"last_fix_lat":${st.lastFixLat},"last_fix_lon":${st.lastFixLon},""" +
            """"last_fix_ts":${st.lastFixTs},"interval_moving_ms":${tp.intervalMovingMs},""" +
            """"interval_stopped_ms":${tp.intervalStoppedMs},"moving_threshold_mps":${tp.movingThresholdMps},""" +
            """"stops_radius_m":${tp.stopsRadiusM},"stops_dwell_min":${tp.stopsDwellMin}}"""
    }

    private fun trackerCountsJson(ctx: Context): String {
        val db = com.diegonmarcos.superapp.maps.MapsDb.get(ctx)
        val st = com.diegonmarcos.superapp.maps.MapsTrackerPrefs(ctx)
        return """{"points":${db.pointCount()},"stops":${db.stopCount()},"last_fix_ts":${st.lastFixTs}}"""
    }

    private fun trackerPointsJson(ctx: Context, n: Int): String {
        val db = com.diegonmarcos.superapp.maps.MapsDb.get(ctx)
        val rows = db.recentPoints(n)
        val sb = StringBuilder("[")
        rows.asReversed().forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("""{"ts":""").append(p.ts).append(',')
            sb.append(""""lat":""").append(p.lat).append(',')
            sb.append(""""lon":""").append(p.lon).append(',')
            sb.append(""""accuracy":""").append(p.accuracy ?: "null").append(',')
            sb.append(""""speed":""").append(p.speed ?: "null").append(',')
            sb.append(""""bearing":""").append(p.bearing ?: "null").append(',')
            sb.append(""""altitude":""").append(p.altitude ?: "null")
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun trackerStopsJson(ctx: Context): String {
        val db = com.diegonmarcos.superapp.maps.MapsDb.get(ctx)
        val now = System.currentTimeMillis()
        val rows = db.stopsBetween(now - 5L * 365L * 24L * 3600_000L, now)
        val sb = StringBuilder("[")
        rows.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("""{"id":""").append(s.id).append(',')
            sb.append(""""started_at":""").append(s.startedAt).append(',')
            sb.append(""""ended_at":""").append(s.endedAt ?: "null").append(',')
            sb.append(""""lat":""").append(s.lat).append(',')
            sb.append(""""lon":""").append(s.lon).append(',')
            sb.append(""""place_name":""").append(s.placeName?.let { "\"" + jsonEscape(it) + "\"" } ?: "null").append(',')
            sb.append(""""neighborhood":""").append(s.neighborhood?.let { "\"" + jsonEscape(it) + "\"" } ?: "null").append(',')
            sb.append(""""city":""").append(s.city?.let { "\"" + jsonEscape(it) + "\"" } ?: "null").append(',')
            sb.append(""""country":""").append(s.country?.let { "\"" + jsonEscape(it) + "\"" } ?: "null")
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun reply(
        w: PrintWriter, status: String, body: String,
        contentType: String = "text/plain",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        w.print("HTTP/1.1 $status\r\n")
        w.print("Content-Type: $contentType; charset=utf-8\r\n")
        w.print("Content-Length: ${bytes.size}\r\n")
        w.print("Connection: close\r\n\r\n")
        w.print(body)
        w.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val k = URLDecoder.decode(it.substring(0, eq), "UTF-8")
            val v = URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            k to v
        }.toMap()
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Runs `logcat -d -t N -v threadtime`. Works without any extra
     *  permission because the app is reading ITS OWN logs (Android
     *  filters logcat by uid for unprivileged processes since 4.1). */
    private fun readLogcat(n: Int): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", n.toString(), "-v", "threadtime"))
        p.inputStream.bufferedReader().readText()
    }.getOrElse { "logcat read failed: $it\n" }

    /** Reads the tail of Trace.kt's trace.log file. */
    private fun readTraceTail(ctx: android.content.Context, n: Int): String = runCatching {
        val f = java.io.File(ctx.getExternalFilesDir(null), "trace/trace.log")
        if (!f.exists()) return@runCatching "trace.log not present\n"
        val all = f.readLines()
        all.takeLast(n).joinToString("\n") + "\n"
    }.getOrElse { "trace read failed: $it\n" }

    /** Lists + concatenates every crash report from BOTH the private
     *  external-files crash dir and (best-effort) the public Downloads
     *  copies that CrashLogger writes via MediaStore. */
    private fun readCrashes(ctx: android.content.Context): String = runCatching {
        val dir = java.io.File(ctx.getExternalFilesDir(null), "crashes")
        if (!dir.exists()) return@runCatching "no crashes directory yet\n"
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) return@runCatching "no crash files\n"
        files.joinToString("\n\n──────────────────────────\n\n") { "[${it.name}]\n" + it.readText() }
    }.getOrElse { "crash read failed: $it\n" }
}
