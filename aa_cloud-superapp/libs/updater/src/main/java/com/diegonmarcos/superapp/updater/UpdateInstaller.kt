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
                    context,
                    sessionId,
                    Intent(context, PackageInstallerReceiver::class.java).apply {
                        setPackage(context.packageName)
                        // The receiver deletes this file once the install is
                        // CONFIRMED. Carrying the exact path beats having the
                        // receiver re-derive it: it needs no fleet lookup (the
                        // fleet list lives in the app's BuildConfig, not this
                        // module's) and it cannot delete the wrong APK.
                        putExtra(PackageInstallerReceiver.EXTRA_APK_PATH, apk.absolutePath)
                        // The gate is keyed by the package we ARMED. Android's
                        // EXTRA_PACKAGE_NAME is not populated on every status,
                        // and a blank one would leave the batch waiting out the
                        // full timeout for an event it already received.
                        putExtra(PackageInstallerReceiver.EXTRA_TARGET_PKG, targetPackage)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (t: Throwable) {
            // Session.close() (what `use` does) only releases OUR handle - the
            // session itself stays alive in the system until it is committed or
            // abandoned, and survives reboots in /data/system/install_sessions.xml.
            // So every failed install used to burn one of the 50 slots Android
            // allows an installer without INSTALL_PACKAGES, permanently, until
            // the whole fleet install died with "Too many active sessions for
            // UID <uid>". Abandon on the way out; the throw still propagates so
            // the caller reports Failed exactly as before.
            runCatching { installer.abandonSession(sessionId) }
                .onFailure { Log.w(tag, "could not abandon session $sessionId", it) }
            throw t
        }
        Log.i(tag, "PackageInstaller session $sessionId committed for ${apk.name}")
    }

    /**
     * How many new install sessions it is safe to open right now.
     *
     * Reaps first, then reports the remaining headroom under [NEAR_CAP]. A
     * background pass asks this before deciding how many apps to install,
     * because the sessions it opens are NOT short-lived: it cannot show the
     * system install dialog (Android 10+ blocks background activity starts), so
     * each one becomes a tap-to-install notification that holds its session
     * until the user answers it. Capping the batch alone would not be enough -
     * passes repeat every few hours and on every launch, so without a budget
     * the held sessions still creep up to Android's 50-session limit.
     */
    internal fun freeSessionSlots(): Int {
        val installer = context.packageManager.packageInstaller
        reapStaleSessions(installer)
        val held = runCatching { installer.mySessions.size }.getOrElse { return 0 }
        return (NEAR_CAP - HEADROOM - held).coerceAtLeast(0)
    }

    /**
     * Abandon our own leftover sessions before opening a new one.
     *
     * Android caps an installer that lacks INSTALL_PACKAGES (signature|privileged
     * - a sideloaded APK cannot hold it) at 50 concurrent sessions, then refuses
     * every new one. The catch above stops NEW leaks; this clears the ones a
     * device is already carrying, so an affected phone heals itself on the next
     * install instead of needing `pm install-abandon` over adb.
     *
     * Only sessions we own are visible here (mySessions), so this can never
     * disturb another installer's work.
     */
    private fun reapStaleSessions(installer: PackageInstaller) {
        val mine = runCatching { installer.mySessions }.getOrNull().orEmpty()
        if (mine.isEmpty()) return

        // Never touch a session a client currently has open - that is a real
        // install in flight. Everything else is ours to reclaim.
        val idle = mine.filterNot { it.isActive }
        if (idle.isEmpty()) return

        val now = System.currentTimeMillis()
        fun createdAt(info: PackageInstaller.SessionInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.createdMillis else 0L

        val victims = when {
            // AT THE CAP the cap IS the problem: createSession fails outright,
            // so "wait until they are an hour old" means an hour of failed
            // installs. That is exactly the state one "Install all" over the
            // fleet produces - dozens of sessions held open by unanswered
            // install prompts, none of them old. Oldest first, freeing enough
            // slots to work in and keeping the newest, which is the one the
            // user is most likely looking at right now. Losing a pending prompt
            // is recoverable; a permanently stuck installer is not.
            mine.size >= NEAR_CAP ->
                idle.sortedBy(::createdAt)
                    .take((idle.size - (NEAR_CAP - HEADROOM)).coerceAtLeast(1))

            // Below the cap there is no urgency, so only reclaim sessions old
            // enough that they cannot still be a prompt awaiting an answer.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                idle.filter { now - it.createdMillis > STALE_SESSION_MS }

            // createdMillis is API 29+. With no age to test, do nothing until
            // the at-cap branch above takes over.
            else -> emptyList()
        }

        var reaped = 0
        for (info in victims) {
            if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess) reaped++
        }
        if (reaped > 0) Log.w(tag, "abandoned $reaped of ${mine.size} install session(s)")
    }

    private companion object {
        /** Android's own limit for an installer without INSTALL_PACKAGES is 50. */
        const val NEAR_CAP = 40
        /** Slots to free when we are already at the cap. */
        const val HEADROOM = 8
        const val STALE_SESSION_MS = 60L * 60L * 1000L
    }
}
