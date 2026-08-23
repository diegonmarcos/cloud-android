package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The constellation-wide debug API: a loopback HTTP surface that EVERY app
 * gets, not just the ones someone remembered to wire it into.
 *
 * Why this exists
 * ---------------
 * An app can always read its OWN logcat with no permission at all — Android
 * filters logcat by uid, and READ_LOGS (needed to see anyone else's) is
 * signature|privileged against the PLATFORM key, which our shared Cloud
 * signing key is not. So no amount of constellation signing lets the SuperApp
 * read cloud-news's logs from outside. The only way to get an app's logs is
 * for that app to serve them itself. Hence: this ships in libs:devtools, which
 * libs:core exposes via `api`, so every app that links core answers on
 * loopback with zero per-app code.
 *
 * Deliberately NOT the same thing as the app-owned DevControlServer
 * ----------------------------------------------------------------
 * SuperApp and cloud-nav each own a big DevControlServer on port 38080 with
 * dozens of app-specific routes under nav, battery and adb. Those stay exactly
 * where they are — this binds a different port range and only serves the
 * handful of endpoints that need no app-specific types whatsoever. Extracting
 * those two servers is a separate job; this one had to be safe to drop into
 * eight apps at once.
 *
 * Auth: one [FleetToken] for the whole fleet, as `Authorization: Bearer <t>`,
 * on every route except /api/system/ping. Loopback alone is not a boundary
 * here — it is device-wide on Android, so any installed app holding INTERNET
 * can reach these ports — and it stopped being defensible entirely once app
 * data started riding these routes beside the logs. One token rather than one
 * per app because fifteen secrets to read one log is how a debug facility goes
 * unused: SuperApp mints it, siblings adopt it over a signature-guarded
 * provider, and the human reads it once from Configs → About.
 *
 * Port: the first free one in [PORT_FIRST, PORT_LAST]. Every app defaults to
 * the same number, so a fixed port would mean only whichever app started first
 * gets a server. A scanner walks the range and asks each port
 * /api/system/info who it is.
 */
object AppDebugServer {
    private const val TAG = "AppDebugServer"

    /** Range start. 38080 is deliberately excluded — it belongs to the
     *  app-owned DevControlServer in SuperApp and cloud-nav.
     *
     *  The range is wide because the engine APKs count too: libs:news and the
     *  other engines link libs:core, so Cloud-Lib-News.apk gets a server of
     *  its own and its process appears here the moment something binds it.
     *  That is the point — NewsEngine runs in that process, so its logs are
     *  only reachable from inside it. Roughly eight apps plus a dozen engines
     *  want a slot, so ten would run out. */
    const val PORT_FIRST = 38090
    const val PORT_LAST = 38139

    /** A client that opens a socket and never sends a request line would
     *  otherwise park the single accept thread forever and take the whole
     *  debug API down with it. */
    private const val SOCKET_TIMEOUT_MS = 5_000

    /** logcat -t is cheap but not free, and `n` arrives from the query
     *  string. Without a ceiling one request can pull the entire ring buffer. */
    private const val MAX_LINES = 20_000
    private const val DEFAULT_LINES = 300

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var port: Int = -1

    /** Actual bound port, or -1 if not running. Not a constant: which port an
     *  app lands on depends on how many constellation apps started first. */
    fun boundPort(): Int = port

    fun isRunning(): Boolean = running.get()

    /**
     * Reachable without the fleet token: liveness plus the applicationId, and
     * nothing else. Everything that returns state or data, /api/docs included,
     * needs the token.
     *
     * The package name is deliberately in the open half. Ports are assigned
     * first-come across [PORT_FIRST]..[PORT_LAST], so port→app is the one fact
     * you need before you can ask anything useful, and gating it makes the
     * facility undebuggable in exactly the case you reach for it — a member
     * that cannot adopt the token answers 401 everywhere and you cannot even
     * tell which app is stuck. It gives an attacker nothing: any app can
     * already enumerate installed packages and portscan loopback, so this only
     * saves them the join.
     */
    private val OPEN_OPS = setOf("system/ping")

    /** App-specific data groups added via [route], keyed by first path segment. */
    private val routes =
        ConcurrentHashMap<String, (String, Map<String, String>) -> String?>()

    /**
     * Register an app's own data group: `route("news") { op, q -> ... }` serves
     * `/api/news/<op>`, returning null for 404. Call it from Application.onCreate.
     *
     * This library deliberately knows nothing about articles, events or
     * contacts. The transport, the fleet auth and the diagnostics are universal;
     * the payloads are not. Handlers run on a socket thread and sit behind the
     * token check above, so an app never authenticates anything itself.
     */
    fun route(group: String, handler: (op: String, query: Map<String, String>) -> String?) {
        routes[group.trim('/')] = handler
    }

    /** `Authorization: Bearer <t>` → `<t>`; null for any other header. Case and
     *  spacing are the client's to get wrong, not ours — curl, OkHttp and a
     *  browser each format this line a little differently, and a parse that only
     *  handled one of them would read as "the token is broken". */
    internal fun bearerOf(header: String): String? {
        if (!header.startsWith("Authorization:", ignoreCase = true)) return null
        val v = header.substringAfter(':').trim()
        if (!v.startsWith("Bearer", ignoreCase = true)) return null
        return v.substring("Bearer".length).trim().ifEmpty { null }
    }

    private fun peersJson(ctx: Context): String {
        val me = ctx.packageName
        val peers = FleetPeers.list(ctx)
        return buildString {
            append("""{"self":"${esc(me)}","count":${peers.size},"peers":[""")
            peers.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("""{"pkg":"${esc(p)}","self":${p == me}}""")
            }
            append("]}")
        }
    }

    private fun appRoute(op: String, query: Map<String, String>): String? {
        val group = op.substringBefore('/')
        val rest = op.substringAfter('/', "")
        return runCatching { routes[group]?.invoke(rest, query) }
            .onFailure { Log.w(TAG, "route $group: $it") }
            .getOrNull()
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        val sock = bindFirstFree()
        if (sock == null) {
            Log.w(TAG, "no free port in $PORT_FIRST..$PORT_LAST — not starting")
            running.set(false)
            return
        }
        server = sock
        port = sock.localPort
        Log.i(TAG, "listening on 127.0.0.1:$port (${app.packageName})")
        thread = Thread({ runServer(app, sock) }, "AppDebug-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        thread?.interrupt()
        server = null
        thread = null
        port = -1
    }

    private fun bindFirstFree(): ServerSocket? {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (p in PORT_FIRST..PORT_LAST) {
            val s = runCatching { ServerSocket(p, 4, loopback) }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    private fun runServer(ctx: Context, sock: ServerSocket) {
        try {
            while (running.get()) {
                val s = sock.accept()
                runCatching { handle(ctx, s) }.onFailure { Log.w(TAG, "handle: $it") }
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "server died: $t")
        } finally {
            runCatching { sock.close() }
            running.set(false)
            port = -1
        }
    }

    private fun handle(ctx: Context, s: Socket) {
        s.soTimeout = SOCKET_TIMEOUT_MS
        s.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(sock.getOutputStream())
            val line = reader.readLine() ?: return
            val parts = line.split(' ')
            if (parts.size < 2) {
                reply(writer, "400 Bad Request", "bad request line\n")
                return
            }
            val rawPath = parts[1]
            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx < 0) rawPath else rawPath.substring(0, qIdx)
            val query =
                if (qIdx < 0) emptyMap<String, String>()
                else parseQuery(rawPath.substring(qIdx + 1))

            // Drain headers so the client sees a clean response rather than a
            // reset while it is still writing, picking the credential up on the
            // way past.
            var bearer: String? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                bearerOf(h)?.let { bearer = it }
            }

            val op = canonicalOp(path)
            // Everything but liveness is fleet-only. The loopback bind stopped
            // being a sufficient boundary the moment app data started riding
            // these routes next to the logs: loopback is device-wide on Android,
            // so any installed app with INTERNET could otherwise read all of it.
            if (op !in OPEN_OPS && !FleetToken.matches(ctx, bearer)) {
                reply(writer, "401 Unauthorized", "unauthorized — Bearer <fleet token>\n")
                return
            }

            when (op) {
                "docs" -> reply(writer, "200 OK", docsJson(ctx), "application/json")
                "system/ping" -> reply(writer, "200 OK", "pong ${ctx.packageName}\n")
                "system/info" -> reply(writer, "200 OK", infoJson(ctx), "application/json")
                "diagnostics/logcat" -> {
                    val n = (query["n"]?.toIntOrNull() ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
                    reply(writer, "200 OK", readLogcat(n))
                }
                "diagnostics/crashes" -> reply(writer, "200 OK", readCrashes(ctx))
                "fleet/peers" -> reply(writer, "200 OK", peersJson(ctx), "application/json")
                "fleet/wake" -> {
                    val pkg = query["pkg"].orEmpty()
                    if (pkg.isBlank()) {
                        reply(writer, "400 Bad Request", "need ?pkg=<applicationId>\n")
                    } else {
                        val ok = FleetPeers.wake(ctx, pkg)
                        reply(
                            writer,
                            if (ok) "200 OK" else "502 Bad Gateway",
                            """{"pkg":"${esc(pkg)}","woken":$ok}""",
                            "application/json",
                        )
                    }
                }
                else -> {
                    val body = appRoute(op, query)
                    if (body != null) reply(writer, "200 OK", body, "application/json")
                    else reply(writer, "404 Not Found", "not found — see /api/docs\n")
                }
            }
        }
    }

    /** Accepts /api/{group}/{op} and the short /{op} aliases, matching the
     *  path styles the app-owned DevControlServer already documents. */
    private fun canonicalOp(path: String): String {
        val stripped = path.removePrefix("/api/").removePrefix("/")
        return when (stripped) {
            "ping" -> "system/ping"
            "info" -> "system/info"
            "logcat" -> "diagnostics/logcat"
            "crashes" -> "diagnostics/crashes"
            "peers" -> "fleet/peers"
            "wake" -> "fleet/wake"
            else -> stripped
        }
    }

    /** Identity WITHOUT the host app's BuildConfig. A library module's own
     *  BuildConfig cannot see the app's, and reading it through an interface
     *  the app must implement would defeat the point of zero per-app wiring —
     *  PackageManager already knows all of this at runtime. */
    private fun infoJson(ctx: Context): String {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val label = runCatching { pm.getApplicationLabel(ctx.applicationInfo).toString() }
            .getOrDefault(pkg)
        val versionCode = when {
            pi == null -> -1L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pi.longVersionCode
            // Braces are required: an annotation cannot sit on a bare `else ->`
            // arm — the parser reads it as a new element, the `when` loses its
            // else branch, and the next line fails to parse.
            else -> {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }
        return buildString {
            append("{")
            append(""""applicationId":"${esc(pkg)}",""")
            append(""""label":"${esc(label)}",""")
            append(""""versionName":"${esc(pi?.versionName ?: "")}",""")
            append(""""versionCode":$versionCode,""")
            append(""""lastUpdateTime":${pi?.lastUpdateTime ?: 0},""")
            append(""""port":$port,""")
            append(""""device":"${esc("${Build.MANUFACTURER} ${Build.MODEL}")}",""")
            append(""""android":"${esc(Build.VERSION.RELEASE)}",""")
            append(""""sdk":${Build.VERSION.SDK_INT}""")
            append("}")
        }
    }

    private fun docsJson(ctx: Context): String = buildString {
        append("{")
        append(""""applicationId":"${esc(ctx.packageName)}",""")
        append(""""port":$port,""")
        append(""""base":"http://127.0.0.1:$port",""")
        append(""""auth":"Bearer <fleet token> — one token for the whole fleet, """)
        append("""shown in SuperApp under Configs → About. /api/system/ping is open.",""")
        append(""""scan":"probe $PORT_FIRST..$PORT_LAST with /api/system/ping — it answers """)
        append("""'pong <applicationId>' unauthenticated, so it maps port to app in one sweep",""")
        append(""""endpoints":[""")
        append("""{"path":"/api/docs","description":"this catalog"},""")
        append("""{"path":"/api/system/ping","description":"liveness + applicationId, """)
        append("""no token needed — scan the port range with this to map port to app"},""")
        append("""{"path":"/api/system/info","description":"applicationId, label, version, bound port, device"},""")
        append("""{"path":"/api/diagnostics/logcat","params":"n=lines (default $DEFAULT_LINES, max $MAX_LINES)","description":"this app's own logcat, threadtime format"},""")
        append("""{"path":"/api/diagnostics/crashes","description":"stored crash reports, newest first"},""")
        append("""{"path":"/api/fleet/peers","description":"installed mesh members"},""")
        append("""{"path":"/api/fleet/wake","params":"pkg=<applicationId>",""")
        append(""""description":"start a member's process via its provider — no activity """)
        append("""launch, so Android background-start restrictions do not apply"}""")
        append("]}")
    }

    /** Runs `logcat -d -t N -v threadtime`. Needs no permission because the
     *  app is reading its OWN logs — Android filters logcat by uid for
     *  unprivileged processes. That is the entire reason this server exists. */
    private fun readLogcat(n: Int): String = runCatching {
        val p = Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-t", n.toString(), "-v", "threadtime"))
        p.inputStream.bufferedReader().readText()
    }.getOrElse { "logcat read failed: $it\n" }

    /** Reads what AppCrashLogger wrote. The directory name is the only
     *  contract between writer and reader. */
    private fun readCrashes(ctx: Context): String = runCatching {
        val dir = File(ctx.getExternalFilesDir(null), "crashes")
        if (!dir.exists()) return@runCatching "no crashes directory yet\n"
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) return@runCatching "no crash files\n"
        files.joinToString("\n\n──────────────────────────\n\n") {
            "[${it.name}]\n" + it.readText()
        }
    }.getOrElse { "crash read failed: $it\n" }

    private fun reply(
        w: PrintWriter,
        status: String,
        body: String,
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

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
