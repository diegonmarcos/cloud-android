package com.diegonmarcos.superapp.updater

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One way to get a verified APK onto the device.
 *
 * The mirror image of [ApkSource]: sources answer "where does the APK come
 * from", channels answer "how does it get installed". Both are ordered lists
 * tried in turn, and both use the same "return null/false = not my job, try
 * the next one" contract — so adding a channel (a future device-owner or
 * privileged-manager install) is adding one object to Fleet.channels.
 */
internal interface InstallChannel {
    val name: String
    /** True if this channel took the install (committed or handed off to a
     *  system UI that will). False = declined; the caller tries the next. */
    fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): Boolean
}

/** Shell install (Shizuku / embedded adb) — no dialog at all. */
internal object ShellInstall : InstallChannel {
    override val name = "shell"
    private const val TAG = "FleetUpdater"

    override fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): Boolean {
        if (!shellInstall(ctx, apk)) return false
        UpdateProgress.update(UpdateProgress.State.Done)
        return true
    }

    /** Zero-dialog install. The shell channel (Shizuku / embedded adb) runs as
     *  uid 2000, which holds INSTALL_PACKAGES — so `pm install` neither shows the
     *  "are you sure you want to update this app" confirm nor gives Play Protect
     *  a chance to stack its scan prompt on top. This is the same channel the
     *  Play-Protect toggle already uses (adbdebug.PackageVerifier).
     *
     *  The APK has to live somewhere shell can READ it: cacheDir is 0700
     *  app-private, so it's staged into external files first and removed after.
     *  Any failure at all returns false and the caller falls back to the
     *  prompting PackageInstaller path — so this can only remove dialogs, never
     *  break an install that used to work.
     *
     *  Blocking (up to ~25s); callers are already off the main thread. */
    private fun shellInstall(ctx: Context, apk: VerifiedApk): Boolean {
        // activeShellChannel() comes from src/shell or src/noshell depending on
        // whether this app declares :libs:shizuku-adb-debug-tools; the stub
        // always returns null, which is the ordinary "no channel" path below.
        val channel = activeShellChannel(ctx) ?: return false
        val src = apk.file
        val stage = File(ctx.getExternalFilesDir(null) ?: return false, "stage-${src.name}")
        return try {
            src.copyTo(stage, overwrite = true)
            // Verify the STAGED copy, not just the download. The downloaded APK
            // is sha-checked against the GHCR digest, but this copy is not, and
            // it goes to external storage — where a full volume truncates it
            // without copyTo throwing on every device. `pm install` then reports
            // INSTALL_PARSE_FAILED_NOT_APK / "failed to load asset path", which
            // reads like a corrupt build and sends you looking at the artifact
            // rather than at the phone's free space.
            if (stage.length() != src.length()) {
                Log.w(TAG, "shell install: staged copy is ${stage.length()} of ${src.length()} bytes " +
                    "(truncated — free space?); falling back to PackageInstaller")
                return false
            }
            // -r reinstall, -d allow version downgrade. Deliberately NOT -g: on a
            // reinstall the runtime grants already carry over, and -g fails the
            // whole install on any permission the platform won't auto-grant.
            val out = (channel.exec(ctx, "pm install -r -d ${stage.absolutePath}") ?: "").trim()
            Log.i(TAG, "shell install via ${channel.name()}: ${out.ifBlank { "no output" }}")
            out.startsWith("Success")
        } catch (t: Throwable) {
            Log.w(TAG, "shell install unavailable, falling back to PackageInstaller", t)
            false
        } finally {
            stage.delete()
        }
    }}

/** PackageInstaller session — always available, but prompts the user. The
 *  result arrives asynchronously at [PackageInstallerReceiver], which is what
 *  moves UpdateProgress off Installing; hence no Done here. */
internal object SessionInstall : InstallChannel {
    override val name = "session"
    override fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): Boolean {
        UpdateInstaller(ctx).install(apk, app.pkg)
        return true
    }
}
