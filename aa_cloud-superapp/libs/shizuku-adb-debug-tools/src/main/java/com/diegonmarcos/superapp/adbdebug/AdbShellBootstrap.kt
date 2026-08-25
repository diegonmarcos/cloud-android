package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import java.util.UUID

/**
 * Bootstrap state for the self-contained app_process shell server
 * ([AdbShellServer]).
 *
 * The server runs in the SHELL domain only because it's launched by
 * `adb` (the one unavoidable privilege source on stock+non-rooted). We
 * own everything else: the server class is in our APK, it binds loopback,
 * and it authenticates with a per-install random token both sides share
 * (the app persists it here; the launch command embeds it).
 *
 * Port + class + nice-name are DATA-DRIVEN from
 * build.json::shizuku_diagnostics.local_server (baked into BuildConfig).
 */
object AdbShellBootstrap {

    fun port(): Int = BuildConfig.ADB_SHELL_SERVER_PORT
    fun serverClass(): String = BuildConfig.ADB_SHELL_SERVER_CLASS
    private fun niceName(): String = BuildConfig.ADB_SHELL_SERVER_NICE

    /** Stable per-install token shared with the server via its launch
     *  args. Generated + persisted on first read. */
    fun token(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(K_TOKEN, null)?.let { if (it.isNotBlank()) return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    /**
     * The exact one-liner to run from any machine with `adb` (Wireless
     * Debugging counts) ONCE per boot. It puts our APK on the classpath
     * and launches [AdbShellServer] via app_process, which therefore runs
     * in the SHELL domain. `nohup … &` + `</dev/null` detaches it so it
     * survives the adb session.
     *
     * The token is embedded so only this app can talk to the server.
     */
    fun serverCommand(ctx: Context): String {
        val pkg = ctx.packageName
        val tok = token(ctx)
        return "adb shell \"CLASSPATH=\$(pm path $pkg | cut -d: -f2) " +
            "nohup app_process /system/bin --nice-name=${niceName()} " +
            "${serverClass()} $tok ${port()} </dev/null >/dev/null 2>&1 &\""
    }

    private const val PREFS   = "adb_shell"
    private const val K_TOKEN = "token"
}
