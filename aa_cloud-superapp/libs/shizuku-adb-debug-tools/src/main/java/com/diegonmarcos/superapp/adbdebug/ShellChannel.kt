package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/**
 * A way to run a shell command in the SHELL SELinux domain (uid 2000) —
 * the only domain that can `dumpsys battery/usb` and read
 * `/sys/class/power_supply/` nodes on a stock, non-rooted device
 * (untrusted_app is denied regardless of the DUMP permission).
 *
 * Three implementations form a ladder (first ready wins):
 *   1. [EmbeddedAdbChannel]  — PRIMARY. Embedded on-device adb client that
 *                              pairs with localhost Wireless-Debugging adbd
 *                              (the LADB approach). Fully self-contained:
 *                              no third-party app, no PC. "We ARE Shizuku."
 *   2. [LocalShellChannel]   — OUR app_process server (AdbShellServer),
 *                              if started via the adb one-liner.
 *   3. [ShizukuShellChannel] — optional fallback when the Shizuku app is
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
    val all: List<ShellChannel> = listOf(EmbeddedAdbChannel, LocalShellChannel, ShizukuShellChannel)

    /** First channel that's ready, or null when neither is available. */
    fun active(ctx: Context): ShellChannel? = all.firstOrNull { it.isReady(ctx) }
}
