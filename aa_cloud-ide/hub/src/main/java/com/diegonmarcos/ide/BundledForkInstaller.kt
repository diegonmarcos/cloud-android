package com.diegonmarcos.ide

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.diegonmarcos.ide.update.PackageInstallerReceiver

/**
 * Installs a bundled app from THIS APK's assets (assets/forks/<domain>.apk —
 * the self-contained Acode / Amaze File Manager rides inside the Cloud-IDE
 * wrapper, seeded at build time by `build.sh bundle-forks`). Android's
 * PackageInstaller is the only no-root path; the user confirms once per app.
 * Status callbacks (success / failure / pending-user-action) surface through
 * the same PackageInstallerReceiver the self-updater uses.
 *
 * Mirrors ea_cloud-comms' BundledForkInstaller.
 */
object BundledForkInstaller {
    private const val TAG = "Ide/BundleInstall"

    /** True if the fork's APK is present in this build's bundle. */
    fun isBundled(ctx: Context, fork: Fork): Boolean =
        try {
            ctx.assets.list("forks")?.contains(fork.assetName) == true
        } catch (_: Throwable) { false }

    /** Stream assets/forks/<domain>.apk into a PackageInstaller session. */
    fun install(ctx: Context, fork: Fork) {
        Log.i(TAG, "installing bundled ${fork.displayName} (${fork.launchPackage}) from assets/forks/${fork.assetName}")
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(fork.launchPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            ctx.assets.open("forks/${fork.assetName}").use { input ->
                session.openWrite("${fork.domain}.apk", 0, -1).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = PendingIntent.getBroadcast(
                ctx, sessionId,
                Intent(ctx, PackageInstallerReceiver::class.java).apply { setPackage(ctx.packageName) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
        Log.i(TAG, "PackageInstaller session $sessionId committed for ${fork.assetName}")
    }
}
