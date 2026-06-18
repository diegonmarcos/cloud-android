package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * PRIMARY, fully self-contained channel — the embedded ADB client.
 *
 * Pairs with the phone's own Wireless-Debugging adbd on 127.0.0.1 ([pair])
 * then connects ([connect]); thereafter [exec] runs commands via the
 * `exec:` service as uid 2000, so dumpsys/usb/sysfs all work — no
 * third-party Shizuku app, no PC. This is the "we ARE Shizuku" channel.
 *
 * Non-rooted reality: Wireless Debugging + the pairing code are entered
 * once per boot (Shizuku and LADB require the same). Only root removes it.
 */
object EmbeddedAdbChannel : ShellChannel {

    override fun name(): String = "embedded-adb"

    override fun isReady(ctx: Context): Boolean =
        runCatching { AdbManager.getInstance(ctx).isConnected }.getOrDefault(false)

    /**
     * Run a command and capture its stdout. Uses the `exec:` service, NOT
     * `shell:`: `shell:` opens an interactive PTY session that never EOFs,
     * so a plain readText() blocks forever; `exec:` runs the command, pipes
     * raw stdout, and closes the stream when it exits — exactly what we want
     * for one-shot capture. A bounded reader thread guarantees the caller
     * (the synchronous DevControlServer handler) can never hang even if a
     * command misbehaves.
     */
    override fun exec(ctx: Context, command: String): String? = runCatching {
        val stream = AdbManager.getInstance(ctx).openStream("exec:$command")
        // Read INCREMENTALLY into a buffer on a worker thread so that, on
        // timeout (or a stream that never EOFs), we return whatever ARRIVED
        // instead of discarding it. Large dumps (full getprop / settings
        // list / dumpsys batterystats) stream in chunks; the old readText()
        // returned "" if the whole thing didn't finish inside the window.
        val acc = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                val input = stream.openInputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(acc) { acc.write(buf, 0, n) }
                }
            }
        }
        reader.start()
        reader.join(EXEC_TIMEOUT_MS)
        val timedOut = reader.isAlive
        if (timedOut) {
            runCatching { stream.close() } // unblock the read → thread exits
            reader.interrupt()
            reader.join(500)
        }
        val text = synchronized(acc) { acc.toString("UTF-8") }
        if (timedOut) "$text\n[…truncated at ${EXEC_TIMEOUT_MS}ms — ${text.length} bytes]" else text
    }.getOrNull()

    private const val EXEC_TIMEOUT_MS = 25_000L

    override fun status(ctx: Context): String =
        if (isReady(ctx)) "Connected — embedded adb to 127.0.0.1 (shell uid 2000)"
        else "Not paired/connected — POST /api/adb/pair then /api/adb/connect (Wireless Debugging)"

    /**
     * Pair with the local adbd using the 6-digit Wireless-Debugging code.
     * [host] is the IP shown in the pairing dialog. Android binds the
     * pairing daemon to the Wi-Fi interface, NOT loopback — so 127.0.0.1
     * gets ECONNREFUSED; pass the device's own shown IP (e.g. 10.0.0.9),
     * which the kernel `local` route table delivers locally ahead of any
     * wg0 route. [port] is the PAIRING port (from the pairing-code dialog).
     */
    fun pair(ctx: Context, host: String, port: Int, code: String): Pair<Boolean, String> = runCatching {
        val ok = onWifi(ctx) { AdbManager.getInstance(ctx).pair(host, port, code) }
        ok to (if (ok) "paired" else "pair returned false")
    }.getOrElse { false to "pair failed: ${it.message}" }

    /**
     * Connect to the local adbd. [host] is the IP from the main Wireless-
     * debugging screen; [port] is the CONNECT port (distinct from the
     * pairing port).
     */
    fun connect(ctx: Context, host: String, port: Int): Pair<Boolean, String> = runCatching {
        val ok = onWifi(ctx) { AdbManager.getInstance(ctx).connect(host, port) }
        ok to (if (ok) "connected" else "connect returned false")
    }.getOrElse { false to "connect failed: ${it.message}" }

    /**
     * Run [block] with the process temporarily pinned to the Wi-Fi
     * (non-VPN) network, so the socket libadb opens bypasses the WireGuard
     * tunnel. This is what makes pairing work with WG ON: the device's
     * own Wireless-Debugging IP (e.g. 10.0.0.9) collides with the WG mesh
     * subnet (10.0.0.0/24), so by default the app's connect to it is
     * captured by wg0's allowedIPs and RSTs. Binding to the Wi-Fi Network
     * routes it straight to the local adbd instead. The persistent
     * AdbConnection socket is created inside this window, so it stays
     * pinned to Wi-Fi; subsequent exec() streams multiplex over it.
     * Restores the previous binding in finally so the rest of the app
     * keeps its normal (WG-routed) networking.
     */
    private fun <T> onWifi(ctx: Context, block: () -> T): T {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return block()
        val wifi = cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null &&
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return block()
        val prev = cm.boundNetworkForProcess
        return try {
            cm.bindProcessToNetwork(wifi)
            block()
        } finally {
            cm.bindProcessToNetwork(prev)
        }
    }
}
