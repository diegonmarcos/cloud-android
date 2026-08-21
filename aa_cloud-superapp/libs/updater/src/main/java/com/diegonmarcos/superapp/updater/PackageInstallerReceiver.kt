package com.diegonmarcos.superapp.updater

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.diegonmarcos.superapp.core.NotificationStore

/**
 * Receives PackageInstaller status callbacks. Forwards the system
 * confirmation Activity on STATUS_PENDING_USER_ACTION; surfaces SUCCESS /
 * FAILURE_* outcomes as a Toast + persistent notification so silent
 * rejects (e.g. signature mismatch after a keystore bump) stop being
 * invisible.
 */
class PackageInstallerReceiver : BroadcastReceiver() {
    private val TAG = "Updater/Receiver"
    private val NOTIF_CHANNEL = "superapp-updater"
    private val NOTIF_ID = 0xC10D

    /**
     * Delete the cached APK once the install is CONFIRMED successful.
     *
     * This is the only point where success is actually known: committing a
     * session only means the bytes were handed over, and a user who declines
     * the system prompt still needs them to retry - deleting any earlier turns
     * every declined dialog into a fresh 10-80MB download.
     *
     * Best-effort: a failed delete costs disk, never correctness.
     */
    private fun reapCachedApk(context: Context, path: String?) {
        if (path.isNullOrEmpty()) return
        val f = java.io.File(path)
        // Only ever touch our own cache - never a file some other caller
        // pointed the installer at.
        if (!f.isFile || f.parentFile != context.cacheDir) return
        val size = f.length()
        if (runCatching { f.delete() }.getOrDefault(false))
            Log.i(TAG, "reaped cached ${f.name}, freed ${size / 1_000_000}MB")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        // Which app was being installed — our own (self-update) or a companion
        // (Cloud-Comms / Cloud-IDE hub). Drives an accurate success message.
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        val appName = when {
            pkg.isBlank() || pkg == context.packageName -> "Cloud SuperApp"
            else -> pkg
        }
        // This same receiver drives installs AND uninstalls (the long-press
        // menu fires PackageInstaller.uninstall with op=uninstall). Uninstall
        // shows no in-app overlay, so it must NOT touch UpdateProgress.
        val isUninstall = intent.getStringExtra(EXTRA_OP) == OP_UNINSTALL
        // Let the batch start the next install. Every branch below is either a
        // terminal outcome or a prompt that is now the user's to answer; in
        // both cases holding the batch any longer serves nothing. Done first so
        // an early `return` in a branch cannot strand a waiting batch.
        if (!isUninstall) {
            // Prefer the package we armed the gate with; EXTRA_PACKAGE_NAME is
            // a fallback for sessions started outside the batch.
            val gateKey = intent.getStringExtra(EXTRA_TARGET_PKG).orEmpty().ifEmpty { pkg }
            if (gateKey.isNotEmpty()) InstallGate.open(gateKey)
        }
        val verb = if (isUninstall) "Uninstall" else "Install"
        Log.i(TAG, "status=$status msg=$message pkg=$pkg op=${if (isUninstall) "uninstall" else "install"}")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The 6h auto-update runs in WorkManager. Android 10+ BLOCKS
                // background activity starts but does NOT throw — startActivity
                // silently no-ops, so a try/catch fallback never fires and the
                // update dies invisibly. Decide by real process importance:
                // foreground → launch the system confirm dialog directly;
                // background → a tap-to-install notification (the only
                // background-safe launch path).
                if (isForeground(context)) {
                    context.startActivity(confirm)
                } else {
                    Log.w(TAG, "confirm launch deferred to notification (app backgrounded)")
                    notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Drop the cached APK now that it is genuinely installed. This
                // is the ONLY point where success is known: commit() only means
                // the session was handed over, and a user who declines the
                // prompt still needs the bytes for a retry. Deleting earlier
                // would turn every declined dialog into a re-download.
                if (!isUninstall) reapCachedApk(context, intent.getStringExtra(EXTRA_APK_PATH))
                // Resolve the install overlay (it sat on "Installing…" while the
                // system installer was up). MainActivity auto-dismisses on Done.
                // Uninstall never raised the overlay, so leave it alone.
                if (!isUninstall) UpdateProgress.update(UpdateProgress.State.Done)
                surface(context, "${verb}ed ✓", "$appName ${verb.lowercase()}ed successfully.",
                    severity = NotificationStore.Sev.INFO)
            }
            else -> {
                val label = when (status) {
                    PackageInstaller.STATUS_FAILURE             -> "FAILURE"
                    PackageInstaller.STATUS_FAILURE_ABORTED     -> "ABORTED"
                    PackageInstaller.STATUS_FAILURE_BLOCKED     -> "BLOCKED"
                    PackageInstaller.STATUS_FAILURE_CONFLICT    -> "CONFLICT (signature/applicationId mismatch — uninstall previous install once)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
                    PackageInstaller.STATUS_FAILURE_INVALID     -> "INVALID"
                    PackageInstaller.STATUS_FAILURE_STORAGE     -> "STORAGE"
                    else -> "status=$status"
                }
                // Resolve the overlay to a visible failure instead of leaving it
                // stuck on "Installing…" for a companion install (installs only).
                if (!isUninstall) UpdateProgress.update(UpdateProgress.State.Failed(message.ifEmpty { label }))
                surface(context, "$verb failed: $label", message.ifEmpty { label },
                    severity = NotificationStore.Sev.ERROR)
            }
        }
    }

    /** True when THIS app's process is currently foreground — the only state in
     *  which a background-context startActivity is honoured on Android 10+.
     *  Anything else (WorkManager auto-update tick with no visible Activity)
     *  must route the confirm Intent through a notification instead. */
    private fun isForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val mine = context.packageName
        return am.runningAppProcesses?.any {
            it.processName == mine &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
    }

    /** Background-safe fallback for STATUS_PENDING_USER_ACTION: a tap-to-install
     *  notification wrapping the system confirm Intent (notifications can launch
     *  activities from the background, unlike startActivity). */
    private fun notifyConfirm(context: Context, confirm: Intent) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                nm.createNotificationChannel(
                    NotificationChannel(NOTIF_CHANNEL, "Updater", NotificationManager.IMPORTANCE_HIGH))
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, NOTIF_ID + 1, confirm, flags)
            val notif = Notification.Builder(context, NOTIF_CHANNEL)
                .setContentTitle("Update ready — tap to install")
                .setContentText("Tap to finish installing the update.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID + 1, notif)
        }
    }

    private fun surface(context: Context, short: String, full: String,
                        severity: String = NotificationStore.Sev.INFO) {
        // Mirror into the in-app feed so the launcher badge AND the
        // Cloud-SuperApp Notifications panel reflect the same event.
        // Without this push the framework notification (and its badge)
        // shows up but the in-app list stays empty — exactly the bug
        // the user reported.
        runCatching {
            NotificationStore.push(
                ctx      = context,
                source   = "Updater",
                title    = short,
                body     = full,
                severity = severity,
            )
        }
        try {
            Toast.makeText(context, short, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) { /* off-Looper thread — skip toast */ }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Updater", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif: Notification = Notification.Builder(context, NOTIF_CHANNEL)
            .setContentTitle(short)
            .setContentText(full)
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        /** Set on the PendingIntent so this receiver can tell an uninstall
         *  (long-press menu) from an install/update and message accordingly. */
        const val EXTRA_OP = "com.diegonmarcos.superapp.updater.OP"
        const val OP_UNINSTALL = "uninstall"

        /** Absolute path of the cached APK, deleted on confirmed success. */
        const val EXTRA_APK_PATH = "apk_path"

        /** Package this session installs, used as the InstallGate key. */
        const val EXTRA_TARGET_PKG = "target_pkg"
    }
}
