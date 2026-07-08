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

    fun silent(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SILENT, true)

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
