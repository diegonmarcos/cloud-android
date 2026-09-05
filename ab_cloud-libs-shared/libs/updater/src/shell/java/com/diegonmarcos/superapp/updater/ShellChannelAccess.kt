package com.diegonmarcos.superapp.updater

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels

/**
 * The real thing — compiled only into apps whose build.json declares
 * :libs:shizuku-adb-debug-tools (today: SuperApp). See the noshell/ twin and
 * the source-set switch in build.gradle for why this is a source set rather
 * than a plain dependency.
 *
 * This is a compile-time reference on purpose: renaming ShellChannels.active
 * breaks the SuperApp build loudly, which reflection would not.
 */
internal fun activeShellChannel(ctx: Context): ShellChannel? = ShellChannels.active(ctx)

/**
 * ESTABLISH a channel, rather than only asking whether one happens to be live.
 *
 * [activeShellChannel] is a probe: it reports the ladder's current state and
 * takes no action. The embedded adb client's connection is process-lifetime
 * state held in AdbManager, so it is gone after every app restart, every
 * reboot, and every Wireless-Debugging toggle (which also rotates adbd's
 * port). An unattended install that only probes therefore finds "no channel"
 * on a phone that is paired and one mDNS query away from being connected —
 * which is exactly what "no install channel accepted" was reporting.
 *
 * So reconnect first, then re-probe. Discovery is mDNS, so no cached port is
 * involved and a rotated port is found again on its own. This is the same
 * reconnect PrivilegedPlaneWorker performs on boot, and it needs NO user step,
 * which is what keeps the install path unattended.
 *
 * Deliberately NOT ShellAccess.ensure: that one may startActivity() to bring
 * up the Shizuku app and returns user-facing prose. Both are wrong for a
 * background worker.
 *
 * Blocks for up to ~10s on discovery — call it off the main thread.
 */
internal fun ensureShellChannel(ctx: Context): ShellChannel? {
    ShellChannels.active(ctx)?.let { return it }
    val (connected, message) = EmbeddedAdbChannel.autoConnect(ctx)
    Log.i("FleetUpdater", "no live shell channel — embedded adb reconnect: $message")
    return if (connected) ShellChannels.active(ctx) else null
}

/** Every rung of the ladder and what it says about itself, for an error that
 *  names WHICH channels were tried and WHY each declined. */
internal fun shellChannelDiagnosis(ctx: Context): String =
    ShellChannels.all.joinToString("; ") { "${it.name()} — ${it.status(ctx)}" }
