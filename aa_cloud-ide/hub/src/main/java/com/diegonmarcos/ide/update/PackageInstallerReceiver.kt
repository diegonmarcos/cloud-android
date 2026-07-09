package com.diegonmarcos.ide.update

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

/**
 * Receives PackageInstaller status callbacks. Forwards the system confirmation
 * Activity on STATUS_PENDING_USER_ACTION; surfaces SUCCESS / FAILURE_* as a
 * Toast + notification so a silent reject (e.g. signature mismatch after a
 * keystore bump) isn't invisible. Trimmed from ea_cloud-superapp's receiver —
 * the thin hub has no in-app NotificationStore feed.
 */
class PackageInstallerReceiver : BroadcastReceiver() {
    private val tag = "Ide/Update/Receiver"
    private val notifChannel = "cloud-ide-updater"
    private val notifId = 0xC1DE

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Log.i(tag, "status=$status msg=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The 6h auto-update runs in WorkManager; Android 10+ BLOCKS
                // background activity starts, so startActivity silently no-ops
                // and the update dies invisibly. Fall back to a tap-to-install
                // notification (launchable from the background) on failure.
                try {
                    context.startActivity(confirm)
                } catch (t: Throwable) {
                    Log.w(tag, "confirm launch blocked (background?): ${t.message}")
                    notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                surface(context, "Update installed ✓", "Cloud-IDE hub installed successfully.")
            else -> {
                val label = when (status) {
                    PackageInstaller.STATUS_FAILURE              -> "FAILURE"
                    PackageInstaller.STATUS_FAILURE_ABORTED      -> "ABORTED"
                    PackageInstaller.STATUS_FAILURE_BLOCKED      -> "BLOCKED"
                    PackageInstaller.STATUS_FAILURE_CONFLICT     -> "CONFLICT (signature/applicationId mismatch — uninstall previous once)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
                    PackageInstaller.STATUS_FAILURE_INVALID      -> "INVALID"
                    PackageInstaller.STATUS_FAILURE_STORAGE      -> "STORAGE"
                    else -> "status=$status"
                }
                surface(context, "Update failed: $label", message.ifEmpty { label })
            }
        }
    }

    /** Background-safe fallback for STATUS_PENDING_USER_ACTION: a tap-to-install
     *  notification wrapping the system confirm Intent (notifications can launch
     *  activities from the background, unlike startActivity). */
    private fun notifyConfirm(context: Context, confirm: Intent) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                nm.createNotificationChannel(
                    NotificationChannel(notifChannel, "Updater", NotificationManager.IMPORTANCE_HIGH))
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, notifId + 1, confirm, flags)
            val notif = Notification.Builder(context, notifChannel)
                .setContentTitle("Update ready — tap to install")
                .setContentText("Tap to finish installing the update.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(notifId + 1, notif)
        }
    }

    private fun surface(context: Context, short: String, full: String) {
        try { Toast.makeText(context, short, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(notifChannel, "Updater", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notif: Notification = Notification.Builder(context, notifChannel)
            .setContentTitle(short)
            .setContentText(full)
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(notifId, notif)
    }
}
