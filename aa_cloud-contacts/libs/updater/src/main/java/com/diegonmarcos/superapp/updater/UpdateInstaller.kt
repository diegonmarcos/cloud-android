package com.diegonmarcos.superapp.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Wraps PackageInstaller — Android's only no-root path to install an APK.
 * The user gets a system prompt to confirm; we cannot bypass it without
 * the system updater permission (F-Droid Privileged Extension territory).
 */
internal class UpdateInstaller(private val context: Context) {
    private val tag = "Updater/Install"

    /**
     * Install [apk]. [targetPackage] is the applicationId of the APK being
     * installed — defaults to our own package for the self-update path, but
     * companion installs (Cloud-Comms / Cloud-IDE hubs) pass the foreign
     * package so PackageInstaller disambiguates correctly.
     */
    fun install(apk: File, targetPackage: String = context.packageName) {
        UpdateProgress.update(UpdateProgress.State.Installing)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // NO setAppPackageName: it's only a hint, and forcing our fork id on
            // a resigned STOCK upstream APK (chat=com.mattermost.rnbeta,
            // matrix=io.element.android.x) makes PackageInstaller reject it with
            // INSTALL_FAILED_INVALID_APK. Let the APK declare its own package —
            // correct for self-update + patched forks + stock upstream alike.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Auto-update toggle (default ON) → silent install; OFF → the
                // normal system prompt. NOT_REQUIRED degrades to a prompt on its
                // own when "install unknown apps" isn't granted, so the About
                // grant row is what turns it truly silent.
                setRequireUserAction(
                    if (AutoUpdatePrefs.silent(context)) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Without an explicit package source, Android 14/15 Enhanced
                // Confirmation Mode treats the install as an untrusted sideload
                // and RESTRICTS the installed app — Settings then refuses to
                // grant it protected roles/permissions with the "restricted
                // setting" denial. Our GHCR fleet updater IS the constellation's
                // app store.
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
                context,
                sessionId,
                Intent(context, PackageInstallerReceiver::class.java).apply {
                    setPackage(context.packageName)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
        Log.i(tag, "PackageInstaller session $sessionId committed for ${apk.name}")
    }
}
