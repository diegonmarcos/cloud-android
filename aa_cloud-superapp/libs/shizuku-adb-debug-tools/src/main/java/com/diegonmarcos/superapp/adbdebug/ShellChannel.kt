package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/**
 * A way to run a shell command in the SHELL SELinux domain (uid 2000) —
 * the only domain that can `dumpsys battery/usb` and read
 * `/sys/class/power_supply/*` on a stock, non-rooted device
 * (untrusted_app is denied regardless of the DUMP permission).
 *
 * Two implementations form a ladder (first ready wins):
 *   1. [LocalShellChannel]   — OUR OWN app_process server (AdbShellServer),
 *                              started once per boot via the adb one-liner.
 *                              Self-contained: no third-party app.
 *   2. [ShizukuShellChannel] — optional fallback when the Shizuku app is
 *                              already running + granted.
 */
interface ShellChannel {
    /** Short id surfaced in API responses ("local-server" / "shizuku"). */
    fun name(): String

    /** True when this channel can execute right now (cheap probe). */
    fun isReady(ctx: Context): Boolean

    /** Run `sh -c <command>` in shell context; null if this channel
     *  couldn't serve it. */
    fun exec(ctx: Context, command: String): String?

    /** One-line human status for /api/adb/status. */
    fun status(ctx: Context): String
}

/** The execution ladder. Order = preference. */
object ShellChannels {
    val all: List<ShellChannel> = listOf(LocalShellChannel, ShizukuShellChannel)

    /** First channel that's ready, or null when neither is available. */
    fun active(ctx: Context): ShellChannel? = all.firstOrNull { it.isReady(ctx) }
}
