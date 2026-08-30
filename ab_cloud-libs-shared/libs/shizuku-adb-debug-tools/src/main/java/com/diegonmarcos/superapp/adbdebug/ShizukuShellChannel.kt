package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/**
 * Optional FALLBACK channel — used only when the self-contained
 * [LocalShellChannel] isn't running but the third-party Shizuku app
 * happens to be up + granted. Thin adapter over [ShizukuAdb] so the
 * ladder treats both uniformly. Lets the user still pull diagnostics
 * immediately (Shizuku already running) while the self-contained server
 * is the permanent path.
 */
object ShizukuShellChannel : ShellChannel {

    override fun name(): String = "shizuku"

    override fun isReady(ctx: Context): Boolean =
        ShizukuAdb.isAvailable() && ShizukuAdb.isGranted()

    override fun exec(ctx: Context, command: String): String? =
        ShizukuAdb.exec(ctx, command)

    override fun status(ctx: Context): String = ShizukuAdb.status()
}
