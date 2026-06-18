package com.diegonmarcos.cloudnav.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.diegonmarcos.cloudnav.core.NotificationStore

/**
 * Receives PackageInstaller status callbacks. Forwards the system
 * confirmation Activity on STATUS_PENDING_USER_ACTION; surfaces SUCCESS /
 * FAILURE_* outcomes as a Toast + notification so a silent reject (e.g. a
 * signature mismatch after a keystore bump) stops being invisible.
 */
class PackageInstallerReceiver : BroadcastReceiver() {
    private val TAG = "Updater/Receiver"
    private val NOTIF_CHANNEL = "cloudnav-updater"
    private val NOTIF_ID = 0xC10D

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        val appName = when {
            pkg.isBlank() || pkg == context.packageName -> "Cloud Nav"
            else -> pkg
        }
        Log.i(TAG, "status=$status msg=$message pkg=$pkg")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateProgress.update(UpdateProgress.State.Done)
                surface(context, "Installed ✓", "$appName installed successfully.",
                    severity = NotificationStore.Sev.INFO)
            }
            else -> {
                val label = when (status) {
                    PackageInstaller.STATUS_FAILURE             -> "FAILURE"
                    PackageInstaller.STATUS_FAILURE_ABORTED     -> "ABORTED"
                    PackageInstaller.STATUS_FAILURE_BLOCKED     -> "BLOCKED"
                    PackageInstaller.STATUS_FAILURE_CONFLICT    -> "CONFLICT (signature/applicationId mismatch — uninstall the previous install once)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
                    PackageInstaller.STATUS_FAILURE_INVALID     -> "INVALID"
                    PackageInstaller.STATUS_FAILURE_STORAGE     -> "STORAGE"
                    else -> "status=$status"
                }
                UpdateProgress.update(UpdateProgress.State.Failed(message.ifEmpty { label }))
                surface(context, "Install failed: $label", message.ifEmpty { label },
                    severity = NotificationStore.Sev.ERROR)
            }
        }
    }

    private fun surface(context: Context, short: String, full: String,
                        severity: String = NotificationStore.Sev.INFO) {
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
}
