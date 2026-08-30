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
     * Call off the main thread: probing the ladder binds services.
     */
    fun ensure(ctx: Context, onReady: () -> Unit): String {
        ShellChannels.active(ctx)?.let {
            onReady()
            return "Using ${it.name()}"
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
        return "Needs SHELL access: install Shizuku and start it, or pair the " +
               "embedded adb channel under Dev tools. Nothing else can write these settings."
    }
}
