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
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val prefs = DevControlPrefs(app)
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

            // Public endpoints
            when (path) {
                "/ping" -> { reply(writer, "200 OK", "pong\n"); return }
                "/info" -> {
                    val body = """{"version":"${BuildConfig.VERSION_NAME}","vc":${BuildConfig.VERSION_CODE},"sha":"${BuildConfig.GIT_SHORT_SHA}","port":${DevControlPrefs(ctx).port}}"""
                    reply(writer, "200 OK", body, "application/json")
                    return
                }
            }

            if (!authed) { reply(writer, "401 Unauthorized", "unauthorized\n"); return }

            // Authenticated endpoints
            when (path) {
                "/state" -> {
                    val snap = DevControlBridge.host()?.stateSnapshot() ?: emptyMap()
                    val body = snap.entries.joinToString(",", "{", "}") {
                        "\"${jsonEscape(it.key)}\":\"${jsonEscape(it.value)}\""
                    }
                    reply(writer, "200 OK", body, "application/json")
                }
                "/haptic" -> {
                    val preset = query["preset"] ?: "tick"
                    DevControlBridge.runOnMain {
                        DevControlBridge.host()?.firePresetHaptic(preset)
                    }
                    reply(writer, "200 OK", """{"ok":true,"preset":"${jsonEscape(preset)}"}""", "application/json")
                }
                "/goto" -> {
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
                "/action" -> {
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
                "/update" -> {
                    DevControlBridge.runOnMain {
                        DevControlBridge.host()?.onActionFromServer("check_updates")
                    }
                    reply(writer, "200 OK", "update queued\n")
                }
                "/restart" -> {
                    reply(writer, "200 OK", "restarting…\n")
                    writer.flush()
                    DevControlBridge.runOnMain { DevControlBridge.restartApp(ctx) }
                }
                else -> reply(writer, "404 Not Found", "not found\n")
            }
        }
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
}
