package com.diegonmarcos.superapp.updater

import android.content.Context
import android.os.Build

/**
 * Runtime toggle for silent auto-update. Default ON — when on, the installer
 * commits with USER_ACTION_NOT_REQUIRED (no system dialog); when off it uses
 * the normal USER_ACTION_REQUIRED prompt. The Configs/About page reads/writes
 * [silent] and surfaces the one-time "install unknown apps" grant that makes
 * NOT_REQUIRED actually silent (without the grant Android transparently falls
 * back to a prompt). Declared default lives in build.json::release.auto_update.
 * ponytail: one boolean in SharedPreferences — no DataStore.
 */
object AutoUpdatePrefs {
    private const val PREFS = "auto_update"
    private const val KEY_SILENT = "silent"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REQUIRE_SILENT = "require_silent"

    /**
     * Master runtime on/off for auto-update. This is what the "Auto-update"
     * toggle controls — the periodic workers (self + constellation) check this
     * at runtime, so flipping it OFF actually stops auto-updating (the baked
     * BuildConfig.AUTO_UPDATE_ENABLED is only the shipped default). OFF ⇒ manual
     * updates only.
     */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, BuildConfig.AUTO_UPDATE_ENABLED)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    fun silent(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SILENT, true)

    /**
     * "Never show me an install confirmation, even if that means not
     * installing." OFF by default, and it must stay that way.
     *
     * This is the opt-in replacement for a compile-time constant that removed
     * the prompting PackageInstaller from [Fleet]'s ladder for the whole fleet
     * — see the ladder comment in Fleet.channels. One APK ships to thousands of
     * devices, so a build flag cannot express "this device". A preference can:
     * it subtracts the fallback only from the device whose owner asked for it,
     * and it is reversible without a release.
     *
     * Only meaningful on a device with a live privileged shell channel
     * (Wireless debugging / Shizuku), because that is the only case where
     * turning the fallback off costs nothing. Anywhere else it costs every
     * update. UI entry point for the toggle:
     * [requireSilent] / [setRequireSilent], intended for a developer or
     * power-user settings row.
     */
    fun requireSilent(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_SILENT, false)

    fun setRequireSilent(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REQUIRE_SILENT, on).apply()

    fun setSilent(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SILENT, on).apply()

    /**
     * Whether the OS will let us install without a prompt — the "install
     * unknown apps" special access. USER_ACTION_NOT_REQUIRED silently degrades
     * to a prompt without it, so this drives the About grant-row status.
     */
    fun canInstallSilently(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()
}
