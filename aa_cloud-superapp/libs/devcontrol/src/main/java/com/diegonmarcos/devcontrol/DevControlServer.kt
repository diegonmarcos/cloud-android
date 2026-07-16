package com.diegonmarcos.devcontrol

import android.content.Context
import android.util.Log
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
                    val ci = DevControl.config
                    val body = """{"version":"${ci.versionName}","vc":${ci.versionCode},"sha":"${ci.gitSha}","port":${DevControlPrefs(ctx).port}}"""
                    reply(writer, "200 OK", body, "application/json")
                    return
                }
                "diagnostics/logcat" -> { reply(writer, "200 OK", readLogcat(query["n"]?.toIntOrNull() ?: 300)); return }
                "diagnostics/trace"  -> { reply(writer, "200 OK", readTraceTail(ctx, query["n"]?.toIntOrNull() ?: 300)); return }
                "diagnostics/crashes" -> { reply(writer, "200 OK", readCrashes(ctx)); return }
                "diagnostics/bundle" -> { reply(writer, "200 OK", diagnosticRecord(ctx), "application/json"); return }
                "diagnostics/download" -> {
                    val name = "cloud-diag-${DevControl.config.applicationId}-${DevControl.config.gitSha}.json"
                    val saved = DiagnosticsPush.downloadBundle(ctx, name, diagnosticRecord(ctx))
                    reply(writer, "200 OK", """{"downloaded":${saved != null},"file":"${jsonEscape(saved ?: "")}"}""", "application/json"); return
                }
                "diagnostics/push" -> {
                    val code = DiagnosticsPush.pushToCloud(diagnosticRecord(ctx))
                    reply(writer, "200 OK", """{"posted":${code in 200..299},"http":$code,"sink":"${jsonEscape(DevControl.config.logSinkUrl)}"}""", "application/json"); return
                }
                "system/about" -> { reply(writer, "200 OK", aboutJson(), "application/json"); return }
            }

            if (!authed) { reply(writer, "401 Unauthorized", "unauthorized\n"); return }

            // Authenticated endpoints
            when (op) {
                "state" -> {
                    val snap = DevControl.host?.stateSnapshot() ?: emptyMap()
                    val body = snap.entries.joinToString(",", "{", "}") {
                        "\"${jsonEscape(it.key)}\":\"${jsonEscape(it.value)}\""
                    }
                    reply(writer, "200 OK", body, "application/json")
                }
                "haptic" -> {
                    val preset = query["preset"] ?: "tick"
                    DevControlBridge.runOnMain {
                        DevControl.host?.firePresetHaptic(preset)
                    }
                    reply(writer, "200 OK", """{"ok":true,"preset":"${jsonEscape(preset)}"}""", "application/json")
                }
                "nav/goto" -> {
                    val target = query["target"]
                    if (target.isNullOrBlank()) {
                        reply(writer, "400 Bad Request", "missing target\n")
                    } else {
                        DevControlBridge.runOnMain {
                            DevControl.host?.onTileFromServer(target)
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
                            DevControl.host?.onActionFromServer(type)
                        }
                        reply(writer, "200 OK", "ok\n")
                    }
                }
                "system/update" -> {
                    DevControlBridge.runOnMain {
                        DevControl.host?.onActionFromServer("check_updates")
                    }
                    reply(writer, "200 OK", "update queued\n")
                }
                "system/restart" -> {
                    reply(writer, "200 OK", "restarting…\n")
                    writer.flush()
                    DevControlBridge.runOnMain { DevControlBridge.restartApp(ctx) }
                }
                else -> {
                    // App-specific ops (phone / battery / sysfs / energy / adb)
                    // are handled by the app-registered provider, so the lib
                    // stays app-agnostic. null → unknown op → 404.
                    val r = DevControl.endpoints?.handle(ctx, op, query)
                    if (r != null) reply(writer, r.status, r.body, r.contentType)
                    else reply(writer, "404 Not Found", "not found — see /api/docs\n")
                }
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
            "about"    -> "system/about"
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
            Spec("system/about",        "GET",  false, "Full About-page data as JSON: app build, device, stack (languages/frameworks/build times), and the folder/sitemap/AST trees", ""),
            Spec("system/update",       "POST", true,  "Trigger an in-app update check (libs:updater)", ""),
            Spec("system/restart",      "POST", true,  "Restart the SuperApp process", ""),
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
            Spec("phone/classify",      "GET",  true,  "Every launchable installed app + the folder PhoneAppClassifier routes it to (debug surface for the Home Apps/Phone tab)", ""),
            Spec("phone/new_apps",      "GET",  true,  "Just the apps that fell to the sink folder (_New Apps) — direct view of what's not yet covered by phone_folders.match_keywords", ""),
            Spec("battery/state",       "GET",  true,  "Full BatterySessionStats.Snapshot — current pct, anchor source (disconnect_event / connect_event / first_read_fallback / (none)), elapsed since anchor, rate, ETA, raw + rescaled current_now, chargerSpec including sysfs liveInputW. The single source of truth for debugging 'why is the rate computing from the moment I opened the page' and similar regressions.", ""),
            Spec("battery/reset_anchor","GET",  true,  "DELETE the persisted session anchor (both unplug/plug). Next read mints a fresh first_read_fallback; the next real plug/unplug cycle overwrites with an authoritative receiver-event anchor. Equivalent to a fresh install for the battery-session machinery.", ""),
            Spec("sysfs/diagnostic",    "GET",  true,  "Per-path readability check for every kernel sysfs/proc file the app touches. Returns ✓ OK + preview when readable, ✗ does-not-exist / not-readable / read-failed otherwise. THIS is the answer to 'why isn't sysfs working even though no perm is needed' — hardened Androids block specific power_supply nodes via SELinux.", ""),
            Spec("battery/properties",  "GET",  true,  "Full dump of every BatteryManager.BATTERY_PROPERTY_* getter + every sticky ACTION_BATTERY_CHANGED extra. This is the path AccuBattery and similar gauges use when sysfs is hardened (Samsung One UI 7+, Pixel A15+) — system-service surface that bypasses the SELinux block. Use it to identify which fields ARE exposed on the current device so we can wire them into BatterySessionStats.", ""),
            Spec("battery/snapshot",    "GET",  true,  "Capture + persist ONE charging snapshot: native fields (level/current/voltage/temp/power) + (if embedded-adb is connected) dumpsys battery truth — Max charging current/voltage, Charging state, IC-auth, and the raw last ACTION_BATTERY_CHANGED line (charge_type/charger_type/hvc/mcc/mcv). Run at <30% cool while charging to capture whether fast-charge (mcv→9000) engages. Also available as a button in Battery Usage Details.", ""),
            Spec("battery/snapshots",   "GET",  true,  "Newest-first history of stored charging snapshots (capped 50) from battery/snapshot — compare across SOC levels to see exactly when/if fast-charge negotiates.", ""),
            Spec("energy/self",         "GET",  true,  "Intra-app energy ledger — which subsystem INSIDE Cloud SuperApp spent the most CPU-ms / wakeups / bytes since the window start (ui.galaxy, music.session, bg.battery_worker, bg.energy_sampler, …). Answers 'what in our own app drains battery'.", ""),
            Spec("energy/attribution",  "GET",  true,  "Device-level Tier-1 watchdog attribution over stored samples: idle baseline mA, marginal mA per state (screen-on / audio / weak-cellular / high-cpu / bright-screen), per-foreground-app avg draw + energy proxy, and our own self cpu/net cost over the window.", ""),
            Spec("energy/samples",      "GET",  true,  "Raw recent energy-watchdog samples (last 200): per-sample whole-device draw_ma + state vector (screen, brightness, foreground pkg, cpu load, signal, wifi, audio).", ""),
            Spec("energy/reset",        "GET",  true,  "Clear the intra-app ledger + the watchdog sample store to start a fresh measurement window.", ""),
            Spec("energy/shizuku",      "GET",  true,  "EXACT per-app mAh via Shizuku (Tier 2) — runs `dumpsys batterystats --charged` in shell context (uid 2000) through the bound ShizukuUserService and parses the per-uid 'Estimated power use (mAh)' section. Returns available/granted/status + the per-app list, or apps=null while binding / when Shizuku isn't running. Ground truth the Tier-1 correlation calibrates against.", ""),
            Spec("adb/status",          "GET",  true,  "libs:shizuku-adb-debug-tools shell-channel ladder: per-channel ready/status for 'embedded-adb' (PRIMARY — our on-device adb client paired to localhost Wireless Debugging, the self-contained 'we ARE Shizuku' path), 'local-server' (our app_process server), and 'shizuku' (fallback); which is active, whether DUMP is held, and the data-driven bundle ids.", ""),
            Spec("adb/pair",            "GET",  true,  "Embedded adb client: pair with the phone's OWN Wireless-Debugging adbd. host=the IP shown in the pairing dialog (Android binds the daemon to the Wi-Fi iface, not loopback — pass that IP e.g. 10.0.0.9; default 127.0.0.1). port=the PAIRING port, code=the 6-digit code. Self-contained bootstrap — no Shizuku app, no PC. Then call /api/adb/connect.", "host=<ip>&port=<pairPort>&code=<6digits>"),
            Spec("adb/connect",         "GET",  true,  "Embedded adb client: connect to the local adbd after pairing. host=IP from the main Wireless-debugging screen (default 127.0.0.1), port=the CONNECT port (distinct from the pairing port). On success the 'embedded-adb' channel goes ready and diagnostics run with zero third-party deps.", "host=<ip>&port=<connectPort>"),
            Spec("adb/autoconnect",     "GET",  true,  "Embedded adb client: auto-discover the local adbd via mDNS (_adb-tls-connect._tcp, advertised by Wireless Debugging) and connect — NO manual port. Needs the device already paired + Wireless Debugging ON. Also runs automatically on app start, so reconnect-after-update is hands-free.", ""),
            Spec("adb/server-command",  "GET",  true,  "Returns the exact one-liner to run ONCE per boot (via adb / Wireless Debugging) to start OUR self-contained shell-domain app_process server (AdbShellServer). This is the only privilege bootstrap; after it the app needs no third-party Shizuku app. {command,port,note}.", ""),
            Spec("adb/diagnostics",     "GET",  true,  "Run a DATA-DRIVEN diagnostic bundle (build.json::shizuku_diagnostics.bundles[]) through the shell-channel ladder (local-server first, Shizuku fallback) and return {bundle,label,channel,ok,results:[{id,cmd,out}]}. Bundles: charger (dumpsys battery+usb, power_supply nodes, typec, charge props), battery, usb, thermal, pd. THE endpoint that surfaces the USB-PD/PPS negotiation behind 'why is the charger at 3W not 35W' when SELinux blocks /sys/class/power_supply/*.", "bundle=charger|battery|usb|thermal|pd (default charger)"),
            Spec("adb/exec",            "GET",  true,  "Generic 'adb shell' passthrough — runs `sh -c <cmd>` in shell context (uid 2000) through the active channel and returns raw stdout. Full adb-equivalent power; token-gated + loopback-only. Use for one-off commands not covered by a bundle.", "cmd=<shell command>"),
            Spec("adb/grant-dump",      "GET",  true,  "Self-grant android.permission.DUMP via `pm grant` through the active shell channel, so dumpsys also works IN-PROCESS. DUMP is signature|privileged|DEVELOPMENT, so pm grant from the shell domain is allowed. Returns {channel,ran,held,output}.", ""),
            Spec("adb/sfc",             "GET",  true,  "Samsung Super Fast Charging verdict — runs the data-driven `samsung-sfc` bundle (getprop model + dumpsys battery) through the shell-channel ladder and parses it into {tier,verdict,reasons[],device_max_watts,high_voltage_engaged,saved_max_current_ma,cable_suspect,sfc_setting_on}. Tiers: FAST_HV (negotiated) / SLOW_5V (charger-PPS or thermal suspect) / FULL_OR_TAPERING (full → healthy, not denied) / NOT_CHARGING. Answers 'why isn't my 100W cable fast-charging' WITHOUT a drained battery: surfaces the device watt-ceiling, whether the SFC toggle is on, and the peak current ever recorded. Parser is pure + JVM-tested against protocol fixtures (libs:shizuku-adb-debug-tools/SfcVerdict).", ""),
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

    /** Full About-page data as JSON — app build, device, stack
     *  (languages/frameworks/build times) and the folder/sitemap/AST trees.
     *  Served by GET /api/system/about (auth-free, same nature as
     *  system/info). All sourced from BuildConfig + Build → no UI needed,
     *  reproducible per build. */
    private fun aboutJson(): String {
        val c = DevControl.config
        val a = c.aboutStack ?: AboutStack()   // app supplies these already-decoded
        return buildString {
            append("{")
            append(""""app":{""")
            append(""""applicationId":"${jsonEscape(c.applicationId)}",""")
            append(""""versionName":"${jsonEscape(c.versionName)}",""")
            append(""""versionCode":${c.versionCode},""")
            append(""""gitSha":"${jsonEscape(c.gitSha)}",""")
            append(""""buildTimestamp":"${jsonEscape(c.buildTimestamp)}",""")
            append(""""buildType":"${jsonEscape(c.buildType)}"},""")
            append(""""device":{""")
            append(""""manufacturer":"${jsonEscape(android.os.Build.MANUFACTURER)}",""")
            append(""""model":"${jsonEscape(android.os.Build.MODEL)}",""")
            append(""""android":"${jsonEscape(android.os.Build.VERSION.RELEASE)}",""")
            append(""""sdk":${android.os.Build.VERSION.SDK_INT}},""")
            append(""""stack":{""")
            append(""""languages":${a.languagesJson.ifBlank { "{}" }},""")
            append(""""frameworks":${a.frameworksJson.ifBlank { "[]" }},""")
            append(""""buildAvgSecs":${a.buildAvgSecs},""")
            append(""""buildLastSecs":${a.buildLastSecs},""")
            append(""""gradleConfigMs":${a.gradleConfigMs}},""")
            append(""""trees":{""")
            append(""""folders":"${jsonEscape(a.folderTree)}",""")
            append(""""sitemap":"${jsonEscape(a.sitemapTree)}",""")
            append(""""ast":"${jsonEscape(a.astTree)}"}""")
            append("}")
        }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Combined debug bundle → the OpenObserve record (reuses the same
     *  logcat/trace/crashes readers). Used by /diagnostics/{bundle,download,push}. */
    private fun diagnosticRecord(ctx: android.content.Context): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        return DiagnosticsPush.buildRecord(
            appId = DevControl.config.applicationId,
            versionName = DevControl.config.versionName,
            versionCode = DevControl.config.versionCode.toString(),
            gitSha = DevControl.config.gitSha,
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
