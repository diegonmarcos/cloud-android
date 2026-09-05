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

    /**
     * Cheap round trip that proves the channel EXECUTES, not merely that a
     * socket is up. [isReady] only reports connection state, so a wedged
     * channel used to be discovered by a multi-megabyte install hanging for
     * the whole exec timeout, once per app.
     */
    fun probe(ctx: Context): Boolean =
        exec(ctx, "echo shell-ok")?.contains("shell-ok") == true

    /**
     * Run [command] with the contents of [stdin] piped to its standard input,
     * and capture stdout. Null when this channel cannot carry binary stdin —
     * the caller then has to find another way.
     *
     * This is the only way to hand a file to a shell-domain command: the shell
     * runs as uid 2000 and can read NEITHER our 0700 app-private cache NOR
     * /Android/data (FUSE-restricted from Android 11), and it cannot be given
     * a staging directory either (/data/local/tmp is 0771 root:shell, so an
     * untrusted_app cannot create anything in it). Bytes over the wire, with
     * no filesystem shared with the shell at all.
     */
    fun execWithStdin(ctx: Context, command: String, stdin: java.io.File): String? = null

    /** One-line human status for /api/adb/status. */
    fun status(ctx: Context): String
}

/** The execution ladder. Order = preference. */
object ShellChannels {
    val all: List<ShellChannel> = listOf(EmbeddedAdbChannel, LocalShellChannel, ShizukuShellChannel)

    /** First channel that's ready, or null when neither is available. */
    fun active(ctx: Context): ShellChannel? = all.firstOrNull { it.isReady(ctx) }
}
