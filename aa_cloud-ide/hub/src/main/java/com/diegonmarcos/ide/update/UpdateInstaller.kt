package com.diegonmarcos.ide.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Wraps PackageInstaller — Android's only no-root path to install an APK. The
 * user gets a system confirmation prompt (can't be bypassed without the system
 * updater privilege). Mirrors ea_cloud-superapp's UpdateInstaller.
 */
internal class UpdateInstaller(private val context: Context) {
    private val tag = "Ide/Update/Install"

    fun install(apk: File) {
        UpdateProgress.update(UpdateProgress.State.Installing)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 14/15 ECM restricts apps installed without an explicit
                // package source (Settings denies them protected roles with the
                // "restricted setting" screen). The hub IS the constellation's store.
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, PackageInstallerReceiver::class.java).apply { setPackage(context.packageName) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
        Log.i(tag, "PackageInstaller session $sessionId committed for ${apk.name}")
    }
}
