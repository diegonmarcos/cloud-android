package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client for OUR self-contained [AdbShellServer] over loopback. This is
 * the PRIMARY channel — fully self-contained, no third-party app. It's
 * "ready" whenever the per-boot app_process server is up (started via the
 * [AdbShellBootstrap.serverCommand] one-liner).
 */
object LocalShellChannel : ShellChannel {

    override fun name(): String = "local-server"

    override fun isReady(ctx: Context): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", AdbShellBootstrap.port()), 300) }
        true
    }.getOrDefault(false)

    override fun exec(ctx: Context, command: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", AdbShellBootstrap.port()), 1000)
            s.soTimeout = 15_000
            val w = s.getOutputStream().bufferedWriter()
            w.write(AdbShellBootstrap.token(ctx)); w.write("\n")
            w.write(Base64.encodeToString(command.toByteArray(), Base64.NO_WRAP)); w.write("\n")
            w.flush()
            s.getInputStream().bufferedReader().readText()
        }
    }.getOrNull()

    override fun status(ctx: Context): String =
        if (isReady(ctx)) "Running — self-contained app_process server on 127.0.0.1:${AdbShellBootstrap.port()} (shell domain)"
        else "Not started — run /api/adb/server-command once per boot via adb/Wireless Debugging"
}
