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
 * dozens of app-specific routes (nav/-star, battery/-star, adb/-star). Those stay exactly
 * where they are — this binds a different port range and only serves the
 * handful of endpoints that need no app-specific types whatsoever. Extracting
 * those two servers is a separate job; this one had to be safe to drop into
 * eight apps at once.
 *
 * Auth: none, matching the existing servers' posture for these same endpoints
 * (ping/info/logcat/crashes are already auth-free there). The bind is
 * 127.0.0.1 only, so reaching it already means code execution on the device.
 * Adding a token would mean fetching a different token per app — fifteen
 * secrets to read one log — for no boundary that the loopback bind doesn't
 * already draw.
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
            val query = if (qIdx < 0) emptyMap() else parseQuery(rawPath.substring(qIdx + 1))

            // Drain headers so the client sees a clean response rather than a
            // reset while it is still writing.
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }

            when (canonicalOp(path)) {
                "docs" -> reply(writer, "200 OK", docsJson(ctx), "application/json")
                "system/ping" -> reply(writer, "200 OK", "pong\n")
                "system/info" -> reply(writer, "200 OK", infoJson(ctx), "application/json")
                "diagnostics/logcat" -> {
                    val n = (query["n"]?.toIntOrNull() ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
                    reply(writer, "200 OK", readLogcat(n))
                }
                "diagnostics/crashes" -> reply(writer, "200 OK", readCrashes(ctx))
                else -> reply(writer, "404 Not Found", "not found — see /api/docs\n")
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
            @Suppress("DEPRECATION")
            else -> pi.versionCode.toLong()
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
        append(""""auth":"none — loopback bind only",""")
        append(""""scan":"probe $PORT_FIRST..$PORT_LAST and GET /api/system/info to identify each app",""")
        append(""""endpoints":[""")
        append("""{"path":"/api/docs","description":"this catalog"},""")
        append("""{"path":"/api/system/ping","description":"liveness — returns pong"},""")
        append("""{"path":"/api/system/info","description":"applicationId, label, version, bound port, device"},""")
        append("""{"path":"/api/diagnostics/logcat","params":"n=lines (default $DEFAULT_LINES, max $MAX_LINES)","description":"this app's own logcat, threadtime format"},""")
        append("""{"path":"/api/diagnostics/crashes","description":"stored crash reports, newest first"}""")
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
