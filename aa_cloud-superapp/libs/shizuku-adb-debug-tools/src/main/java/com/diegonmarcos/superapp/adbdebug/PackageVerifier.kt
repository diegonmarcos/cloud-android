package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.provider.Settings

/**
 * Turns off the Play Protect install-time scan prompt — the
 * "Scan app with Play Protect? / Install without scanning" dialog that
 * interrupts every fleet install on a GMS device.
 *
 * That dialog is NOT something an installer can opt out of: no manifest
 * flag, no signature, no F-Droid-style repo index suppresses it. It is
 * driven by three Settings.Global values that GmsCore's package verifier
 * reads, and writing them needs WRITE_SECURE_SETTINGS — a signature
 * permission no sideloaded APK can hold. Hence the shell channel: the
 * SHELL uid (2000) holds it, so `settings put global …` through
 * [ShellChannels] does what the app itself cannot.
 *
 * READING is unprivileged, so [state] works with no channel at all and the
 * UI can always show the truth. Only [setScanning] needs a channel.
 *
 * This is device state, not app state: it survives SuperApp being
 * uninstalled, and it applies to EVERY installer on the device. Flipping
 * it back is the same one call.
 */
object PackageVerifier {

    /** The user's answer to the scan prompt. 1 = scan, -1 = never ask. */
    private const val CONSENT = "package_verifier_user_consent"

    /** Master switch for the verifier. 0 = off. */
    private const val ENABLE = "package_verifier_enable"

    /** Whether session/adb installs are verified too. 0 = no. */
    private const val ADB = "verifier_verify_adb_installs"

    /**
     * Present state, read straight from Settings.Global (no privilege
     * needed). [on] is the only thing the UI needs; the raw triple is kept
     * for the diagnostics line, because a device where GMS re-armed only
     * one of the three should read as "partly on" rather than silently
     * flipping back to a green label.
     */
    data class State(val consent: Int, val enable: Int, val adb: Int) {
        /** Scanning is off only when nothing is left to re-arm it. */
        val on: Boolean get() = consent >= 0 || enable != 0 || adb != 0
        fun describe(): String =
            if (!on) "Play Protect install scan: OFF (consent=$consent enable=$enable adb=$adb)"
            else "Play Protect install scan: ON (consent=$consent enable=$enable adb=$adb)"
    }

    fun state(ctx: Context): State {
        fun g(key: String, def: Int) =
            runCatching { Settings.Global.getInt(ctx.contentResolver, key, def) }.getOrDefault(def)
        // Defaults match a stock GMS device: consented, enabled, verifying.
        return State(g(CONSENT, 1), g(ENABLE, 1), g(ADB, 1))
    }

    /** The commands that put the device into [scan] state. Pure, so it's testable. */
    internal fun commands(scan: Boolean): List<String> = listOf(
        "settings put global $CONSENT ${if (scan) 1 else -1}",
        "settings put global $ENABLE ${if (scan) 1 else 0}",
        "settings put global $ADB ${if (scan) 1 else 0}",
    )

    data class Result(val ok: Boolean, val channel: String, val output: String, val state: State)

    /**
     * Set install-time scanning on or off. Runs off the main thread —
     * binding Shizuku blocks. Returns what actually happened plus the
     * re-read state, so the caller never has to assume the write landed.
     */
    fun setScanning(ctx: Context, scan: Boolean): Result {
        val channel = ShellChannels.active(ctx)
            ?: return Result(false, "none",
                "No shell channel. Start Shizuku (or the embedded adb channel) and grant it, then retry.",
                state(ctx))
        val out = commands(scan).joinToString("\n") { cmd ->
            cmd + " → " + (channel.exec(ctx, cmd) ?: "no output").trim().ifBlank { "ok" }
        }
        val after = state(ctx)
        return Result(after.on == scan, channel.name(), out, after)
    }
}
