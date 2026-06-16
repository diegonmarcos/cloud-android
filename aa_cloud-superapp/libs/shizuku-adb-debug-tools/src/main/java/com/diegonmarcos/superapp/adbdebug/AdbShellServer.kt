package com.diegonmarcos.superapp.adbdebug

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/**
 * Self-contained "adb shell" server — the SELF-CONTAINED replacement for
 * the third-party Shizuku app.
 *
 * This class is NOT instantiated as part of the app. It's launched by
 * `app_process` from the adb one-liner in [AdbShellBootstrap.serverCommand]:
 *
 *   adb shell "CLASSPATH=$(pm path <pkg>|cut -d: -f2) \
 *     app_process /system/bin --nice-name=superapp-adb \
 *     com.diegonmarcos.superapp.adbdebug.AdbShellServer <token> <port>"
 *
 * Because adb gives the launching process the SHELL uid (2000) and the
 * `shell` SELinux domain, this server — and every command it runs —
 * inherits that domain. THAT is what lets it `dumpsys battery/usb` and
 * read `/sys/class/power_supply/*`, which the app's own `untrusted_app`
 * domain is denied no matter what permission it holds.
 *
 * It runs no Android framework that needs a Context/Looper — only raw
 * sockets + Runtime.exec — so it's safe to host in a bare app_process VM.
 *
 * Protocol (loopback only, one command per connection):
 *   → line 1: token  (must equal the launch-arg token)
 *   → line 2: base64(command)
 *   ← combined stdout (or "ERR: <stderr>")
 */
object AdbShellServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0)
        if (token.isNullOrBlank()) {
            System.err.println("usage: AdbShellServer <token> [port]")
            return
        }
        val port = args.getOrNull(1)?.toIntOrNull() ?: 38099

        val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        val uid = runCatching { android.os.Process.myUid() }.getOrDefault(-1)
        System.out.println("superapp-adb: listening 127.0.0.1:$port (uid=$uid)")
        System.out.flush()

        while (true) {
            val s = try { server.accept() } catch (t: Throwable) { break }
            runCatching { handle(s, token) }
            runCatching { s.close() }
        }
    }

    private fun handle(socket: Socket, token: String) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = socket.getOutputStream().bufferedWriter()

        val gotToken = reader.readLine()
        if (gotToken != token) {
            writer.write("ERR: bad token\n"); writer.flush(); return
        }
        val b64 = reader.readLine()
        if (b64.isNullOrBlank()) {
            writer.write("ERR: empty command\n"); writer.flush(); return
        }
        val cmd = runCatching { String(Base64.getDecoder().decode(b64.trim())) }
            .getOrElse { writer.write("ERR: bad base64\n"); writer.flush(); return }

        val out = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val o = p.inputStream.bufferedReader().readText()
            val e = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (e.isNotBlank() && o.isBlank()) "ERR: $e" else o
        }.getOrElse { "ERR: ${it.message}" }

        writer.write(out)
        writer.flush()
    }
}
