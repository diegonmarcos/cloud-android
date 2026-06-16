package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/**
 * PRIMARY, fully self-contained channel — the embedded ADB client.
 *
 * Pairs with the phone's own Wireless-Debugging adbd on 127.0.0.1 ([pair])
 * then connects ([connect]); thereafter [exec] opens a `shell:` stream as
 * uid 2000, so dumpsys/usb/sysfs all work — no third-party Shizuku app,
 * no PC. This is the "we ARE Shizuku" channel.
 *
 * Non-rooted reality: Wireless Debugging + the pairing code are entered
 * once per boot (Shizuku and LADB require the same). Only root removes it.
 */
object EmbeddedAdbChannel : ShellChannel {

    override fun name(): String = "embedded-adb"

    override fun isReady(ctx: Context): Boolean =
        runCatching { AdbManager.getInstance(ctx).isConnected }.getOrDefault(false)

    override fun exec(ctx: Context, command: String): String? = runCatching {
        val stream = AdbManager.getInstance(ctx).openStream("shell:$command")
        stream.openInputStream().bufferedReader().use { it.readText() }
    }.getOrNull()

    override fun status(ctx: Context): String =
        if (isReady(ctx)) "Connected — embedded adb to 127.0.0.1 (shell uid 2000)"
        else "Not paired/connected — POST /api/adb/pair then /api/adb/connect (Wireless Debugging)"

    /**
     * Pair with the local adbd using the 6-digit Wireless-Debugging code.
     * [port] is the PAIRING port (the one shown in the pairing-code dialog).
     */
    fun pair(ctx: Context, port: Int, code: String): Pair<Boolean, String> = runCatching {
        val ok = AdbManager.getInstance(ctx).pair("127.0.0.1", port, code)
        ok to (if (ok) "paired" else "pair returned false")
    }.getOrElse { false to "pair failed: ${it.message}" }

    /**
     * Connect to the local adbd. [port] is the CONNECT port (shown on the
     * main Wireless-debugging screen, distinct from the pairing port).
     */
    fun connect(ctx: Context, port: Int): Pair<Boolean, String> = runCatching {
        val ok = AdbManager.getInstance(ctx).connect("127.0.0.1", port)
        ok to (if (ok) "connected" else "connect returned false")
    }.getOrElse { false to "connect failed: ${it.message}" }
}
