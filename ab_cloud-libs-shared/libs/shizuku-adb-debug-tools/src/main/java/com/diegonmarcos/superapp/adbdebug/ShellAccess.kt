package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.content.Intent

/**
 * Getting a shell channel, rather than just reporting that there isn't one.
 *
 * [ShellChannels.active] answers "is one ready?" — useful for a status line,
 * useless for a button. A button that needs SHELL privilege should START the
 * flow that grants it, so this walks the ladder from "nothing installed" up to
 * "ready" and takes the one action that moves the user forward.
 *
 * The ladder is INTERNAL FIRST, matching [ShellChannels.all]:
 *   1. a channel that is already live;
 *   2. the app's own embedded adb client, reconnected over mDNS with the keys
 *      from its one-time pairing — self-contained, no other app, no user step;
 *   3. the external Shizuku app, if it happens to be installed and running;
 *   4. an honest "unavailable", saying what each plane needs.
 */
object ShellAccess {

    /** Shizuku's own package. Not configuration — it is the app's identity. */
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /**
     * Make a shell channel usable, starting whatever flow is needed, and return
     * the line to show the user.
     *
     * [onReady] fires when a channel becomes usable WITHOUT another tap — i.e.
     * the user approves the Shizuku prompt — so the caller can finish the job it
     * was already attempting. It lands on a binder thread; marshal to the UI
     * thread yourself. It is not called when the user must act elsewhere first
     * (start the Shizuku app, pair wireless debugging), because there is no
     * callback for those.
     *
     * Call off the main thread: probing the ladder binds services and
     * autoConnect runs an mDNS discovery.
     */
    fun ensure(ctx: Context, onReady: () -> Unit): String {
        ShellChannels.active(ctx)?.let {
            onReady()
            return "Using ${it.name()}"
        }
        // INTERNAL BEFORE EXTERNAL. This used to jump straight to Shizuku, so a
        // phone running the self-contained plane — no Shizuku installed, by
        // design — was told to go install Shizuku for a channel it already owns.
        // "No channel right now" is almost always "not reconnected since the
        // last reboot": the embedded client keeps the keys from the one-time
        // pairing and re-attaches over mDNS with no user step at all. That is
        // the same call PrivilegedPlaneWorker makes on boot, so this is the
        // button doing what the boot path already does rather than a second
        // mechanism.
        val (connected, adbMsg) = EmbeddedAdbChannel.autoConnect(ctx)
        if (connected) ShellChannels.active(ctx)?.let {
            onReady()
            return "Using ${it.name()} — $adbMsg"
        }
        if (ShizukuAdb.isAvailable()) {
            // Service is up, we just have no grant — this is the one case with a
            // real prompt and a real callback.
            if (!ShizukuAdb.isGranted()) {
                ShizukuAdb.requestPermission { onReady() }
                return "Approve the Shizuku prompt to continue…"
            }
            // Granted but the bind hasn't landed yet; force it.
            return if (ShizukuAdb.bindBlocking(ctx)) { onReady(); "Connected to Shizuku" }
                   else "Shizuku granted but the service did not bind — restart Shizuku"
        }
        // Shizuku isn't running. If it is at least installed, open it: its service
        // has to be started by the user once per boot, and no API can do that.
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) {
            runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return "Start the Shizuku service, then tap again"
        }
        // Both planes are out. Lead with the internal one — it is the supported
        // path and the only one that needs no other app — and carry what the
        // embedded client actually said, because "Wireless Debugging is off" and
        // "never paired" need different answers from the user.
        return "Needs SHELL access: turn Wireless Debugging on and pair the embedded " +
               "adb channel under Dev tools (embedded adb: $adbMsg), or install Shizuku " +
               "and start it. Nothing else can write these settings."
    }
}
