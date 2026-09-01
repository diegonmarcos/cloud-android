package com.diegonmarcos.ide

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Minimal loopback-only dev API server (raw ServerSocket, no deps).
 * Bound to 127.0.0.1:8790 so it is never network-exposed.
 * Returns a plain-text banner + logcat -d -t 500 on any GET.
 */
object DevApiServer {

    const val PORT = 8790

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val t = Thread({
            runCatching {
                val ss = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                while (running) {
                    runCatching {
                        val client = ss.accept()
                        Thread({
                            runCatching {
                                client.use {
                                    // Drain the request (ignore it)
                                    it.getInputStream().bufferedReader().readLine()
                                    val logcat = runCatching {
                                        Runtime.getRuntime()
                                            .exec(arrayOf("logcat", "-d", "-t", "500", "-v", "threadtime"))
                                            .inputStream.bufferedReader().readText()
                                    }.getOrElse { "logcat read failed: $it\n" }
                                    val body = "cloud-ide dev-api  v${BuildConfig.VERSION_NAME}" +
                                        " sha ${BuildConfig.GIT_SHORT_SHA}\n\n$logcat"
                                    val resp = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/plain; charset=utf-8\r\n" +
                                        "Content-Length: ${body.toByteArray().size}\r\n" +
                                        "Connection: close\r\n\r\n" + body
                                    it.getOutputStream().write(resp.toByteArray())
                                }
                            }
                        }, "dev-api-worker").also { it.isDaemon = true }.start()
                    }
                }
            }
        }, "dev-api-server")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
