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
                // Android 14/15 ECM restricts apps installed without an explicit
                // package source (Settings denies them protected roles with the
                // "restricted setting" screen). The hub IS the constellation's store.
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        }
        reapStaleSessions(installer)
        val sessionId = installer.createSession(params)
        try {
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
        } catch (t: Throwable) {
            // Session.close() (what `use` does) only releases OUR handle - the
            // session itself stays alive in the system until it is committed or
            // abandoned, and survives reboots in /data/system/install_sessions.xml.
            // So every failed install burned one of the 50 slots Android allows an
            // installer without INSTALL_PACKAGES, permanently, until the fleet
            // install died with "Too many active sessions for UID <uid>". Abandon
            // on the way out; the throw still propagates so the caller reports
            // Failed exactly as before.
            runCatching { installer.abandonSession(sessionId) }
                .onFailure { Log.w(tag, "could not abandon session $sessionId", it) }
            throw t
        }
        Log.i(tag, "PackageInstaller session $sessionId committed for ${apk.name}")
    }

    /**
     * Abandon our own leftover sessions before opening a new one.
     *
     * Android caps an installer that lacks INSTALL_PACKAGES (signature|privileged
     * - a sideloaded APK can never hold it) at 50 concurrent sessions, then
     * refuses every new one with "Too many active sessions for UID <uid>". The
     * catch above stops NEW leaks; this clears what a device already carries, so
     * an affected phone heals itself on the next install instead of needing
     * `pm install-abandon` over adb.
     *
     * Only sessions we own are visible here (mySessions), so this can never
     * disturb another installer's work.
     */
    private fun reapStaleSessions(installer: PackageInstaller) {
        val mine = runCatching { installer.mySessions }.getOrNull().orEmpty()
        if (mine.isEmpty()) return
        val now = System.currentTimeMillis()
        var reaped = 0
        for (info in mine) {
            // isActive = a client currently has it open, i.e. an install really
            // is in flight. Never touch those.
            if (info.isActive) continue
            val stale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // A committed session waiting on the user's Install prompt is
                // inactive too, so age is what separates "user is deciding" from
                // "leaked hours ago". No prompt lives for an hour.
                now - info.createdMillis > STALE_SESSION_MS
            } else {
                // createdMillis is API 29+. Below that age is unknowable, so only
                // step in when the cap is actually the problem.
                mine.size >= NEAR_CAP
            }
            if (!stale) continue
            if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess) reaped++
        }
        if (reaped > 0) Log.w(tag, "abandoned $reaped stale install session(s) of ${mine.size}")
    }

    private companion object {
        /** Android's own limit for an installer without INSTALL_PACKAGES is 50. */
        const val NEAR_CAP = 40
        const val STALE_SESSION_MS = 60L * 60L * 1000L
    }
}
