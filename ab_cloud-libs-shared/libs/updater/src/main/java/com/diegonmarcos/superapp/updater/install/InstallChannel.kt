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
     *  THE APK IS STREAMED, NEVER HANDED OVER AS A PATH.
     *
     *  This used to copy the APK into getExternalFilesDir() and run
     *  `pm install <path>`. uid 2000 cannot read that path: /Android/data is
     *  FUSE-restricted from Android 11 — and `pm` reports the failure on
     *  STDERR, which the adb `exec:` service does not carry. The observed
     *  result was the worst possible one: ZERO bytes of output and a hang
     *  until the exec timeout, once per app, after which every install fell
     *  through to the prompting fallback and the user saw a dialog with
     *  Wireless debugging switched on. Hence `pm install -S <size>`, which
     *  reads the package from stdin exactly as `adb install` does: nothing is
     *  shared with the shell through the filesystem, and nothing is left
     *  behind on shared storage. (/data/local/tmp is not an option either —
     *  0771 root:shell, so an untrusted_app cannot create anything in it.)
     *
     *  Any failure at all returns the reason, and the caller reports it — or,
     *  where the prompting fallback is still enabled, falls through to the
     *  PackageInstaller path.
     *
     *  Blocking (the transfer plus the commit, plus up to ~10s of mDNS if a
     *  reconnect is needed); callers are already off the main thread. */
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
        // PROVE THE CHANNEL EXECUTES BEFORE SENDING IT TENS OF MEGABYTES.
        // isReady()/ensureShellChannel() only establish that a socket is up. A
        // channel that is connected but wedged answers nothing, and finding
        // that out via the install itself means paying the full timeout per
        // app — a whole fleet pass of dead wall clock. A trivial round trip
        // costs milliseconds, and fails in seconds rather than tens of them.
        if (!channel.probe(ctx)) {
            return "${channel.name()} is connected but did not answer a trivial `echo` " +
                   "round trip — the channel is up but not usable"
        }
        val src = apk.file
        return try {
            // -r reinstall, -d allow version downgrade, -S <size> read the
            // package from stdin. Deliberately NOT -g: on a reinstall the
            // runtime grants already carry over, and -g fails the whole install
            // on any permission the platform won't auto-grant.
            // 2>&1 because `exec:` carries stdout only — without it every `pm`
            // diagnostic is discarded and any failure reads as "no output",
            // which is precisely how the old staged-path bug hid for so long.
            val out = channel.execWithStdin(
                ctx, "pm install -r -d -S ${src.length()} 2>&1", src,
            )?.trim()
                ?: return "${channel.name()} cannot stream a package over stdin, and there is no " +
                          "path on this device that this app can write and shell (uid 2000) " +
                          "can read"
            Log.i(TAG, "shell install via ${channel.name()}: ${out.ifBlank { "no output" }}")
            if (out.startsWith("Success")) null
            else "${channel.name()} streamed ${src.length()} bytes to `pm install -S` and it " +
                 "answered: " + out.ifBlank { "nothing at all — the channel dropped mid-command" }
        } catch (t: Throwable) {
            Log.w(TAG, "shell install threw", t)
            "${channel.name()} threw ${t.javaClass.simpleName}: ${t.message}"
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
