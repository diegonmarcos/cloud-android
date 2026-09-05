package com.diegonmarcos.superapp.adbdebug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Wireless Debugging, ON and OFF, from inside the app.
 *
 * The Permissions screen could only ever OPEN the Settings page for this — a
 * deep link and a hope. But this app already writes `Settings.Global` directly
 * for Play Protect ([PackageVerifier]), and `PrivilegedPlaneWorker` already
 * writes THIS EXACT KEY on every boot to bring the privileged plane back up.
 * So the capability was there and the user simply could not reach it.
 *
 * ## The key
 * `adb_wifi_enabled` — `Settings.Global.ADB_WIFI_ENABLED`, @hide since
 * Android 11 (API 30) where Wireless Debugging was introduced, unchanged
 * through SDK 35. It is the value `AdbDebuggingManager` watches to start and
 * stop the TLS adbd listener. Confirmed inside this repo rather than from
 * memory: `PrivilegedPlaneWorker.enableWirelessDebugging` writes exactly this
 * constant, and the whole boot-time privileged plane depends on it working.
 *
 * `adb_enabled` is NOT a companion of it and is deliberately untouched — that
 * is USB debugging, a different switch with different consequences, and
 * flipping it as a side effect of a Wi-Fi toggle would be a surprise.
 *
 * ## Same ladder, same honesty rule as PackageVerifier
 * Ourselves (WRITE_SECURE_SETTINGS) → live shell channel → an explicit
 * "unavailable". READING needs no privilege at all, so the UI can always show
 * the truth even when it cannot change it, and every [Result] re-reads the
 * value rather than assuming the write landed. A toggle that reports success
 * it did not achieve is worse than no toggle.
 */
object WirelessDebugging {

    /** `Settings.Global.ADB_WIFI_ENABLED` — @hide, so named here. */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Unprivileged read. Absent key ⇒ off, which is the platform default and
     *  what every device reports after a reboot. */
    fun isOn(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** What actually happened. [channel] names the mechanism that worked, or
     *  "none"; [ok] is the re-read agreeing with what was asked, never the
     *  write call merely not throwing. */
    data class Result(val ok: Boolean, val channel: String, val detail: String, val on: Boolean)

    /**
     * Turn Wireless Debugging [on] or off.
     *
     * ## Turning it OFF can cut the branch we are sitting on
     * The embedded adb channel — the thing that makes installs silent and that
     * `pm grant`s this app's own permissions — talks to the adbd this switch
     * starts. Turning it off ends that channel until it is turned back on and
     * the client reconnects. That is a legitimate thing to want, and the UI
     * says so before doing it, but there is one case where it must not happen
     * at all: mid-install. [busy] is how the caller says so; the toggle refuses
     * rather than stranding a half-finished install with no channel to finish
     * it on.
     *
     * Blocking (binding Shizuku does). Call off the main thread.
     */
    fun set(ctx: Context, on: Boolean, busy: Boolean = false): Result {
        if (!on && busy) return Result(false, "refused",
            "An install is in flight and it may be using this channel. " +
            "Turning Wireless Debugging off now could leave it half-finished. " +
            "Wait for it to end, then try again.", isOn(ctx))

        val want = if (on) 1 else 0

        // OURSELVES FIRST — same reasoning as PackageVerifier.setScanning: on a
        // phone the privileged plane has ever reached, WRITE_SECURE_SETTINGS is
        // already granted, so this needs no channel, no binding and — crucially
        // for turning it back ON — no Wireless Debugging, which is precisely
        // the thing that is off.
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED) {
            val wrote = runCatching {
                Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, want)
            }.getOrElse { false }
            if (isOn(ctx) == on) return Result(true, "self (WRITE_SECURE_SETTINGS)",
                "$ADB_WIFI_ENABLED=$want (${if (wrote) "accepted" else "rejected but state agrees"})",
                on)
        }

        // Then the shell ladder. ShellChannels.active is a pure PROBE — it asks
        // isReady and never reconnects — which is exactly the bug ffe5b157c
        // fixed for the install path: a paired phone whose embedded client had
        // not reconnected since app start reported "no channel" without ever
        // trying to bring one up. So the reconnect is a rung here too, and
        // autoConnect discovers a rotated adbd port over mDNS rather than
        // trusting a remembered one.
        //
        // Note the shape of the OFF case: the channel this runs on is the one
        // the write is about to close, so the command succeeds and the channel
        // dies. That is expected, and the re-read below — which needs no
        // channel at all — is what decides the verdict.
        val channel = ShellChannels.active(ctx)
            ?: (if (EmbeddedAdbChannel.autoConnect(ctx).first) EmbeddedAdbChannel else null)
            ?: return Result(false, "none",
                "No way to write this setting: WRITE_SECURE_SETTINGS is not granted to " +
                "this app and no shell channel is up. Use \"Open Wireless Debugging\" " +
                "and flip it in system settings; pairing once from there grants the " +
                "permission and this toggle works directly from then on.",
                isOn(ctx))
        val out = channel.exec(ctx, "settings put global $ADB_WIFI_ENABLED $want")
        val after = isOn(ctx)
        return Result(after == on, channel.name(),
            "settings put global $ADB_WIFI_ENABLED $want → " +
                ((out ?: "no output").trim().ifBlank { "ok" }),
            after)
    }
}
