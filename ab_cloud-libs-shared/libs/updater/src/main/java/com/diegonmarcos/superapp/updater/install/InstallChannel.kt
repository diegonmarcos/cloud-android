package com.diegonmarcos.superapp.updater.install

import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.ensureShellChannel
import com.diegonmarcos.superapp.updater.shellChannelDiagnosis
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.source.ApkSource
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
    /**
     * Null if this channel took the install (committed or handed off to a
     * system UI that will). Otherwise a sentence saying WHY it declined, and
     * the caller tries the next.
     *
     * This used to return a bare false, which is how "no install channel
     * accepted <pkg>" came to be the entire story a user got: never connected,
     * pairing lost, `pm install` refused, a truncated staging copy — every
     * distinct cause collapsed into the same one bit.
     */
    fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String?
}

/** Shell install (Shizuku / embedded adb) — no dialog at all. */
internal object ShellInstall : InstallChannel {
    override val name = "shell"
    private const val TAG = "FleetUpdater"

    override fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String? {
        // The blocking `pm install` call below can take up to ~25s. Without
        // this, the overlay is left showing the last Downloading frame (or an
        // even staler one) for the whole install, which reads as frozen —
        // exactly the "batch progress looks stuck" bug this exists to end.
        UpdateProgress.update(UpdateProgress.State.Installing)
        val declined = shellInstall(ctx, apk)
        if (declined != null) return declined
        UpdateProgress.update(UpdateProgress.State.Done)
        return null
    }

    /** Zero-dialog install. The shell channel (Shizuku / embedded adb) runs as
     *  uid 2000, which holds INSTALL_PACKAGES — so `pm install` neither shows the
     *  "are you sure you want to update this app" confirm nor gives Play Protect
     *  a chance to stack its scan prompt on top. This is the same channel the
     *  Play-Protect toggle already uses (adbdebug.PackageVerifier).
     *
     *  The APK has to live somewhere shell can READ it: cacheDir is 0700
     *  app-private, so it's staged into external files first and removed after.
     *  Any failure at all returns the reason, and the caller reports it — or,
     *  where the prompting fallback is still enabled, falls through to the
     *  PackageInstaller path.
     *
     *  Blocking (up to ~25s, plus up to ~10s of mDNS if a reconnect is needed);
     *  callers are already off the main thread. */
    private fun shellInstall(ctx: Context, apk: VerifiedApk): String? {
        // ESTABLISH, don't merely probe. activeShellChannel() only reported
        // whether a channel happened to be live, so an unattended install found
        // "none" on a paired phone whose embedded adb client had simply not
        // reconnected since the last app start — the connection is in-process
        // state, so it does not survive one. ensureShellChannel() runs the same
        // no-user-step mDNS reconnect the boot path does, then re-probes. It
        // comes from src/shell or src/noshell depending on whether this app
        // declares :libs:shizuku-adb-debug-tools; the stub always returns null.
        val channel = ensureShellChannel(ctx)
            ?: return "no privileged shell channel could be established, even after a " +
                      "reconnect attempt (${shellChannelDiagnosis(ctx)})"
        val src = apk.file
        val stage = File(
            ctx.getExternalFilesDir(null)
                ?: return "no external files directory to stage the APK in, and shell (uid 2000) " +
                          "cannot read the 0700 app-private cache",
            "stage-${src.name}",
        )
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
                return "the staged copy is ${stage.length()} of ${src.length()} bytes " +
                       "(truncated — external storage full?)"
            }
            // -r reinstall, -d allow version downgrade. Deliberately NOT -g: on a
            // reinstall the runtime grants already carry over, and -g fails the
            // whole install on any permission the platform won't auto-grant.
            val out = (channel.exec(ctx, "pm install -r -d ${stage.absolutePath}") ?: "").trim()
            Log.i(TAG, "shell install via ${channel.name()}: ${out.ifBlank { "no output" }}")
            if (out.startsWith("Success")) null
            else "${channel.name()} ran `pm install` and it answered: " +
                 out.ifBlank { "nothing at all — the channel dropped mid-command" }
        } catch (t: Throwable) {
            Log.w(TAG, "shell install threw", t)
            "${channel.name()} threw ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            stage.delete()
        }
    }}

/** PackageInstaller session — always available, but prompts the user. The
 *  result arrives asynchronously at [PackageInstallerReceiver], which is what
 *  moves UpdateProgress off Installing; hence no Done here. */
internal object SessionInstall : InstallChannel {
    override val name = "session"
    override fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String? {
        UpdateInstaller(ctx).install(apk, app.pkg)
        return null
    }
}
