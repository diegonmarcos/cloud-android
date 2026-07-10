package com.diegonmarcos.cloudnav.configs

import android.content.Context
import android.util.Log
import com.diegonmarcos.cloudnav.BuildConfig
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

            // Normalise both legacy flat paths AND the new /api/{group}/{op}
            // layout to a single canonical op name. The op name is what
            // the routing + the /api/docs catalog below both key on, so
            // there's exactly ONE source of truth for which endpoints
            // exist.
            val op = canonicalOp(path)

            // ENDPOINT CATALOG — declared once, used by both the
            // routing switch AND /api/docs. Adding an endpoint = one
            // entry here + one branch in the switch below. /api/docs
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
                "diagnostics/bundle" -> { reply(writer, "200 OK", diagnosticRecord(ctx), "application/json"); return }
                "diagnostics/download" -> {
                    val name = "cloud-diag-${BuildConfig.APPLICATION_ID}-${BuildConfig.GIT_SHORT_SHA}.json"
                    val saved = DiagnosticsPush.downloadBundle(ctx, name, diagnosticRecord(ctx))
                    reply(writer, "200 OK", """{"downloaded":${saved != null},"file":"${jsonEscape(saved ?: "")}"}""", "application/json"); return
                }
                "diagnostics/push" -> {
                    val code = DiagnosticsPush.pushToCloud(diagnosticRecord(ctx))
                    reply(writer, "200 OK", """{"posted":${code in 200..299},"http":$code,"sink":"${jsonEscape(BuildConfig.LOG_SINK_URL)}"}""", "application/json"); return
                }
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
                "battery/state" -> { reply(writer, "200 OK", batteryStateJson(ctx), "application/json") }
                "battery/reset_anchor" -> {
                    com.diegonmarcos.cloudnav.BatterySessionStats.resetAnchor(ctx)
                    reply(writer, "200 OK", """{"ok":true,"message":"anchor cleared — next plug/unplug will re-mint via PowerStateReceiver"}""", "application/json")
                }
                "sysfs/diagnostic" -> { reply(writer, "200 OK", sysfsDiagnosticJson(), "application/json") }
                "battery/properties" -> { reply(writer, "200 OK", batteryPropertiesJson(ctx), "application/json") }
                "energy/self" -> { reply(writer, "200 OK", energySelfJson(), "application/json") }
                "energy/attribution" -> { reply(writer, "200 OK", energyAttributionJson(ctx), "application/json") }
                "energy/samples" -> { reply(writer, "200 OK", energySamplesJson(ctx), "application/json") }
                "energy/reset" -> {
                    com.diegonmarcos.cloudnav.EnergyLedger.reset()
                    runCatching { com.diegonmarcos.cloudnav.EnergyStore(ctx).clear() }
                    reply(writer, "200 OK", """{"ok":true,"message":"energy ledger + sample store cleared"}""", "application/json")
                }
                else -> reply(writer, "404 Not Found", "not found — see /api/docs\n")
            }
        }
    }

    /** Map both `/ping` (legacy flat) and `/api/system/ping` (new) to
     *  the canonical op name `system/ping`. The routing + the /api/docs
     *  catalog both key on this canonical name. New endpoints SHOULD
     *  be added under the v1/{group}/{op} form only; legacy aliases
     *  are kept so existing curls in the user's Configs/About panel
     *  don't break. */
    private fun canonicalOp(path: String): String {
        // Strip /api/ prefix if present.
        val stripped = path.removePrefix("/api/").removePrefix("/")
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
            Spec("system/update",       "POST", true,  "Trigger an in-app update check", ""),
            Spec("system/restart",      "POST", true,  "Restart the Cloud Nav process", ""),
            Spec("diagnostics/logcat",  "GET",  false, "Recent logcat lines, threadtime format", "n=lines (default 300)"),
            Spec("diagnostics/trace",   "GET",  false, "Tail of Trace.kt's trace.log", "n=lines (default 300)"),
            Spec("diagnostics/crashes", "GET",  false, "All crash files concatenated, newest first", ""),
            Spec("diagnostics/bundle",  "GET",  false, "Full debug bundle (logcat+trace+crashes+device) as one OpenObserve JSON record", ""),
            Spec("diagnostics/download","GET",  false, "Write the debug bundle to public Downloads; returns the filename", ""),
            Spec("diagnostics/push",    "GET",  false, "POST the debug bundle to the cloud log sink (OpenObserve via build.json::diagnostics.log_sink_url)", ""),
            Spec("state",               "GET",  true,  "Snapshot of MainActivity's live state map (section, label, mode, …)", ""),
            Spec("haptic",              "POST", true,  "Fire a named haptic preset on the device", "preset=name (default 'tick')"),
            Spec("nav/goto",            "POST", true,  "Navigate to a tile target (section:X / page:X/Y / action:X / url)", "target=string"),
            Spec("nav/action",          "POST", true,  "Fire one of MainActivity.onActionFromServer's verbs", "type=string"),
            Spec("tracker/prefs",       "GET",  true,  "Tracker calibration prefs + enabled flag + last fix coords", ""),
            Spec("tracker/counts",      "GET",  true,  "DB row counts: points + stops + last-fix timestamp", ""),
            Spec("tracker/points",      "GET",  true,  "Recent GPS points reverse-chrono with accuracy + speed", "n=count (default 20)"),
            Spec("tracker/stops",       "GET",  true,  "All stops in the local DB with start/end + reverse-geocoded place", ""),
            Spec("battery/state",       "GET",  true,  "Full BatterySessionStats.Snapshot — current pct, anchor source (disconnect_event / connect_event / first_read_fallback / (none)), elapsed since anchor, rate, ETA, raw + rescaled current_now, chargerSpec including sysfs liveInputW. The single source of truth for debugging 'why is the rate computing from the moment I opened the page' and similar regressions.", ""),
            Spec("battery/reset_anchor","GET",  true,  "DELETE the persisted session anchor (both unplug/plug). Next read mints a fresh first_read_fallback; the next real plug/unplug cycle overwrites with an authoritative receiver-event anchor. Equivalent to a fresh install for the battery-session machinery.", ""),
            Spec("sysfs/diagnostic",    "GET",  true,  "Per-path readability check for every kernel sysfs/proc file the app touches. Returns ✓ OK + preview when readable, ✗ does-not-exist / not-readable / read-failed otherwise. THIS is the answer to 'why isn't sysfs working even though no perm is needed' — hardened Androids block specific power_supply nodes via SELinux.", ""),
            Spec("battery/properties",  "GET",  true,  "Full dump of every BatteryManager.BATTERY_PROPERTY_* getter + every sticky ACTION_BATTERY_CHANGED extra. This is the path AccuBattery and similar gauges use when sysfs is hardened (Samsung One UI 7+, Pixel A15+) — system-service surface that bypasses the SELinux block. Use it to identify which fields ARE exposed on the current device so we can wire them into BatterySessionStats.", ""),
            Spec("energy/self",         "GET",  true,  "Intra-app energy ledger — which subsystem INSIDE Cloud Nav spent the most CPU-ms / wakeups / bytes since the window start (ui.galaxy, bg.battery_worker, bg.energy_sampler, location.tracker, …). Answers 'what in our own app drains battery'.", ""),
            Spec("energy/attribution",  "GET",  true,  "Device-level Tier-1 watchdog attribution over stored samples: idle baseline mA, marginal mA per state (screen-on / audio / weak-cellular / high-cpu / bright-screen), per-foreground-app avg draw + energy proxy, and our own self cpu/net cost over the window.", ""),
            Spec("energy/samples",      "GET",  true,  "Raw recent energy-watchdog samples (last 200): per-sample whole-device draw_ma + state vector (screen, brightness, foreground pkg, cpu load, signal, wifi, audio).", ""),
            Spec("energy/reset",        "GET",  true,  "Clear the intra-app ledger + the watchdog sample store to start a fresh measurement window.", ""),
        )
        val sb = StringBuilder()
        sb.append("""{"port":""").append(port).append(',')
        sb.append(""""base":"http://127.0.0.1:""").append(port).append("\",")
        sb.append(""""auth":"Bearer <token> in Authorization header (token visible in Configs/About → Dev control HTTP)",""")
        sb.append(""""path_styles":["/api/{group}/{op} (preferred)","/{op} (legacy flat aliases — same handler)"],""")
        sb.append(""""endpoints":[""")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append(""""op":"""").append(jsonEscape(r.op)).append("\",")
            sb.append(""""path":"/api/""").append(jsonEscape(r.op)).append("\",")
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
        val tp = com.diegonmarcos.cloudnav.maps.MapsTrackingPrefs(ctx)
        val st = com.diegonmarcos.cloudnav.maps.MapsTrackerPrefs(ctx)
        return """{"enabled":${st.enabled},"last_fix_lat":${st.lastFixLat},"last_fix_lon":${st.lastFixLon},""" +
            """"last_fix_ts":${st.lastFixTs},"interval_moving_ms":${tp.intervalMovingMs},""" +
            """"interval_stopped_ms":${tp.intervalStoppedMs},"moving_threshold_mps":${tp.movingThresholdMps},""" +
            """"stops_radius_m":${tp.stopsRadiusM},"stops_dwell_min":${tp.stopsDwellMin}}"""
    }

    private fun trackerCountsJson(ctx: Context): String {
        val db = com.diegonmarcos.cloudnav.maps.MapsDb.get(ctx)
        val st = com.diegonmarcos.cloudnav.maps.MapsTrackerPrefs(ctx)
        return """{"points":${db.pointCount()},"stops":${db.stopCount()},"last_fix_ts":${st.lastFixTs}}"""
    }

    private fun trackerPointsJson(ctx: Context, n: Int): String {
        val db = com.diegonmarcos.cloudnav.maps.MapsDb.get(ctx)
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
        val db = com.diegonmarcos.cloudnav.maps.MapsDb.get(ctx)
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

    /** Intra-app energy ledger — which subsystem inside Cloud Nav
     *  spent the most CPU / wakeups / bytes since the window start. */
    private fun energySelfJson(): String {
        val (since, stats) = com.diegonmarcos.cloudnav.EnergyLedger.snapshot()
        val sorted = stats.entries.sortedByDescending { it.value.cpuNanos }
        val sb = StringBuilder("""{"since_ms":""").append(since).append(',')
        sb.append(""""tags":[""")
        var first = true
        for ((tag, s) in sorted) {
            if (!first) sb.append(','); first = false
            sb.append("""{"tag":"""").append(jsonEscape(tag)).append('"').append(',')
            sb.append(""""cpu_ms":""").append(s.cpuNanos / 1_000_000L).append(',')
            sb.append(""""calls":""").append(s.calls).append(',')
            sb.append(""""wakeups":""").append(s.wakeups).append(',')
            sb.append(""""bytes":""").append(s.bytes).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Device-level state → draw attribution + per-foreground-app +
     *  our own self cost, computed over the stored sample window. */
    private fun energyAttributionJson(ctx: Context): String {
        val m = com.diegonmarcos.cloudnav.EnergyWatchdog.attribution(ctx)
        return mapToJson(m)
    }

    /** Raw recent watchdog samples (last 200). */
    private fun energySamplesJson(ctx: Context): String {
        val rows = runCatching { com.diegonmarcos.cloudnav.EnergyStore(ctx).recent(200) }
            .getOrDefault(emptyList())
        val sb = StringBuilder("[")
        var first = true
        for (s in rows) {
            if (!first) sb.append(','); first = false
            sb.append('{')
            sb.append(""""ts":""").append(s.ts).append(',')
            sb.append(""""draw_ma":""").append(s.drawMa).append(',')
            sb.append(""""batt_pct":""").append(s.battPct).append(',')
            sb.append(""""screen_on":""").append(s.screenOn).append(',')
            sb.append(""""brightness":""").append(s.brightness).append(',')
            sb.append(""""charging":""").append(s.charging).append(',')
            sb.append(""""fg_pkg":"""").append(jsonEscape(s.fgPkg)).append('"').append(',')
            sb.append(""""cpu_load":""").append(s.cpuLoadPct).append(',')
            sb.append(""""mobile_dbm":""").append(s.mobileSignalDbm).append(',')
            sb.append(""""wifi_rssi":""").append(s.wifiRssi).append(',')
            sb.append(""""audio":""").append(s.audioActive).append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Minimal recursive Map/List/primitive → JSON for the attribution
     *  payload (it's already a clean Map<String,Any> tree). */
    private fun mapToJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${jsonEscape(v)}\""
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            "\"${jsonEscape(it.key.toString())}\":${mapToJson(it.value)}"
        }
        is List<*> -> v.joinToString(",", "[", "]") { mapToJson(it) }
        else -> "\"${jsonEscape(v.toString())}\""
    }

    /** Full BatterySessionStats.Snapshot as JSON. The single source of
     *  truth for debugging battery-rate / anchor regressions —
     *  surfaces EVERY field of the Snapshot data class plus the
     *  derived chargerSpec subobject (so callers can see whether
     *  liveInputW came from sysfs or only from dumpsys). */
    private fun batteryStateJson(ctx: Context): String {
        val s = com.diegonmarcos.cloudnav.BatterySessionStats.read(ctx)
        val sb = StringBuilder("{")
        sb.append(""""isCharging":""").append(s.isCharging).append(',')
        sb.append(""""curPct":""").append(s.curPct).append(',')
        sb.append(""""nowMs":""").append(s.nowMs).append(',')
        // Discharge anchor block
        sb.append(""""unplugTs":""").append(s.unplugTs).append(',')
        sb.append(""""unplugPct":""").append(s.unplugPct).append(',')
        sb.append(""""unplugAnchorSource":"""").append(jsonEscape(s.unplugAnchorSource)).append('"').append(',')
        sb.append(""""elapsedMs":""").append(s.elapsedMs).append(',')
        sb.append(""""consumedPct":""").append(s.consumedPct).append(',')
        sb.append(""""ratePerMin":""").append(s.ratePerMin).append(',')
        sb.append(""""etaMs":""").append(s.etaMs).append(',')
        sb.append(""""etaDrainedAt":""").append(s.etaDrainedAt).append(',')
        // Charge anchor block
        sb.append(""""plugTs":""").append(s.plugTs).append(',')
        sb.append(""""plugPct":""").append(s.plugPct).append(',')
        sb.append(""""plugAnchorSource":"""").append(jsonEscape(s.plugAnchorSource)).append('"').append(',')
        sb.append(""""chargeElapsedMs":""").append(s.chargeElapsedMs).append(',')
        sb.append(""""gainedPct":""").append(s.gainedPct).append(',')
        sb.append(""""chargeRatePerMin":""").append(s.chargeRatePerMin).append(',')
        sb.append(""""etaFullMs":""").append(s.etaFullMs).append(',')
        sb.append(""""etaFullAt":""").append(s.etaFullAt).append(',')
        // Power readings (BatteryManager)
        sb.append(""""voltageMv":""").append(s.voltageMv).append(',')
        sb.append(""""currentRaw":""").append(s.currentRaw).append(',')
        sb.append(""""currentUa":""").append(s.currentUa).append(',')
        sb.append(""""rescaledMaToUa":""").append(s.rescaledMaToUa).append(',')
        sb.append(""""powerW":""").append(s.powerW).append(',')
        sb.append(""""powerWSource":"""").append(jsonEscape(s.powerWSource)).append('"').append(',')
        // BatteryManager system-service surface (AccuBattery's trick)
        sb.append(""""batteryTempC":""").append(s.batteryTempC).append(',')
        sb.append(""""chargeCounterUah":""").append(s.chargeCounterUah).append(',')
        sb.append(""""cycleCount":""").append(s.cycleCount).append(',')
        sb.append(""""peakChargeCounterUah":""").append(s.peakChargeCounterUah).append(',')
        sb.append(""""cumulativeChargedUah":""").append(s.cumulativeChargedUah).append(',')
        // Charger spec subobject
        sb.append(""""chargerSpec":{""")
        sb.append(""""maxCurrentUa":""").append(s.chargerSpec.maxCurrentUa).append(',')
        sb.append(""""maxVoltageUv":""").append(s.chargerSpec.maxVoltageUv).append(',')
        sb.append(""""maxPowerW":""").append(s.chargerSpec.maxPowerW).append(',')
        sb.append(""""liveInputW":""").append(s.chargerSpec.liveInputW).append(',')
        sb.append(""""source":"""").append(jsonEscape(s.chargerSpec.source)).append('"').append(',')
        sb.append(""""usbPowered":""").append(s.chargerSpec.usbPowered).append(',')
        sb.append(""""acPowered":""").append(s.chargerSpec.acPowered).append(',')
        sb.append(""""wirelessPowered":""").append(s.chargerSpec.wirelessPowered)
        sb.append('}')
        sb.append('}')
        return sb.toString()
    }

    /** Full dump of every BatteryManager.BATTERY_PROPERTY_* getter +
     *  every sticky ACTION_BATTERY_CHANGED extra. Path bypasses the
     *  SELinux block on /sys/class/power_supply because BatteryManager
     *  goes through the system_server service binder, not raw sysfs.
     *  Used to identify what's actually exposed on a hardened Samsung
     *  / Pixel so we can wire BatterySessionStats to the system-
     *  service surface instead of the kernel files. */
    private fun batteryPropertiesJson(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val sticky = ctx.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val sb = StringBuilder("{")

        // BatteryManager (system-service path, immune to sysfs blocking)
        sb.append(""""batteryManager":{""")
        var firstBm = true
        fun appendIntProp(label: String, prop: Int) {
            if (!firstBm) sb.append(','); firstBm = false
            val v = runCatching { bm?.getIntProperty(prop) }.getOrNull()
            sb.append('"').append(label).append("\":").append(v ?: "null")
        }
        fun appendLongProp(label: String, prop: Int) {
            if (!firstBm) sb.append(','); firstBm = false
            val v = runCatching { bm?.getLongProperty(prop) }.getOrNull()
            sb.append('"').append(label).append("\":").append(v ?: "null")
        }
        appendIntProp("current_now_uA",          android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        appendIntProp("current_average_uA",      android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        appendIntProp("capacity_pct",            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        appendIntProp("status",                  android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        appendLongProp("charge_counter_uAh",     android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        appendLongProp("energy_counter_nWh",     android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        if (!firstBm) sb.append(','); firstBm = false
        sb.append(""""isCharging":""").append(bm?.isCharging ?: "null")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            sb.append(',')
            val t = runCatching { bm?.computeChargeTimeRemaining() }.getOrNull()
            sb.append(""""compute_charge_time_remaining_ms":""").append(t ?: "null")
        }
        sb.append('}')

        // Sticky ACTION_BATTERY_CHANGED extras (broadcast surface;
        // some fields like cycle_count + charging_status are API 31+
        // and only land in the extras bundle on devices that support
        // them — we read by string key for forward-compat).
        sb.append(""","stickyExtras":{""")
        if (sticky == null) {
            sb.append(""""_present":false""")
        } else {
            sb.append(""""_present":true""")
            fun appendIntExtra(label: String, key: String, default: Int = Int.MIN_VALUE) {
                sb.append(',').append('"').append(label).append("\":")
                val v = sticky.getIntExtra(key, default)
                sb.append(if (v == default) "null" else v)
            }
            fun appendBoolExtra(label: String, key: String) {
                sb.append(',').append('"').append(label).append("\":")
                sb.append(sticky.getBooleanExtra(key, false))
            }
            fun appendStringExtra(label: String, key: String) {
                sb.append(',').append('"').append(label).append("\":")
                val v = sticky.getStringExtra(key)
                if (v == null) sb.append("null")
                else sb.append('"').append(jsonEscape(v)).append('"')
            }
            appendIntExtra("level",        android.os.BatteryManager.EXTRA_LEVEL)
            appendIntExtra("scale",        android.os.BatteryManager.EXTRA_SCALE)
            appendIntExtra("status",       android.os.BatteryManager.EXTRA_STATUS)
            appendIntExtra("plugged",      android.os.BatteryManager.EXTRA_PLUGGED)
            appendIntExtra("health",       android.os.BatteryManager.EXTRA_HEALTH)
            appendIntExtra("voltage_mV",   android.os.BatteryManager.EXTRA_VOLTAGE)
            appendIntExtra("temperature_dC", android.os.BatteryManager.EXTRA_TEMPERATURE)
            appendStringExtra("technology", android.os.BatteryManager.EXTRA_TECHNOLOGY)
            appendBoolExtra("present",     android.os.BatteryManager.EXTRA_PRESENT)
            // API 31+ extras read by canonical string key (constants
            // not always resolvable at compileSdk < 31). If absent,
            // getIntExtra returns the default (MIN_VALUE) which we
            // surface as null.
            appendIntExtra("cycle_count",     "android.os.extra.CYCLE_COUNT")
            appendIntExtra("charging_status", "android.os.extra.CHARGING_STATUS")
            appendIntExtra("max_charging_current_uA", "android.os.extra.MAX_CHARGING_CURRENT")
            appendIntExtra("max_charging_voltage_uV", "android.os.extra.MAX_CHARGING_VOLTAGE")
        }
        sb.append('}')

        sb.append('}')
        return sb.toString()
    }

    /** Per-path readability snapshot of every kernel sysfs/proc file
     *  the app touches. Sorted: failing paths first so the user sees
     *  what's blocked on their device immediately, then OKs.
     *  The frontline answer to "AccuBattery's trick isn't working
     *  for me" — exposes whether the kernel actually allows the
     *  read or whether SELinux is denying it. */
    private fun sysfsDiagnosticJson(): String {
        val all = com.diegonmarcos.cloudnav.SysfsProc.sysfsReadDiagnostic()
        val sorted = all.sortedBy { (_, status) -> if (status.startsWith("✗")) 0 else 1 }
        val sb = StringBuilder("""{"paths":[""")
        var first = true
        for ((path, status) in sorted) {
            if (!first) sb.append(','); first = false
            sb.append('{')
            sb.append(""""path":"""").append(jsonEscape(path)).append('"').append(',')
            sb.append(""""status":"""").append(jsonEscape(status)).append('"')
            sb.append('}')
        }
        sb.append("],")
        val total = all.size
        val ok = all.count { (_, status) -> status.startsWith("✓") }
        sb.append(""""summary":{"total":""").append(total).append(',')
        sb.append(""""ok":""").append(ok).append(',')
        sb.append(""""blocked":""").append(total - ok).append('}')
        sb.append('}')
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

    /** Combined debug bundle → the OpenObserve record (reuses the same
     *  logcat/trace/crashes readers). Used by /diagnostics/{bundle,download,push}
     *  AND the About → Debug channel UI (internal for that reuse). */
    internal fun diagnosticRecord(ctx: android.content.Context): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        return DiagnosticsPush.buildRecord(
            appId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toString(),
            gitSha = BuildConfig.GIT_SHORT_SHA,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            androidRelease = android.os.Build.VERSION.RELEASE,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            tsIso = ts,
            logcat = readLogcat(500),
            trace = readTraceTail(ctx, 500),
            crashes = readCrashes(ctx),
        )
    }

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
