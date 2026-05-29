package com.diegonmarcos.superapp.updater

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

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Log.i(TAG, "status=$status msg=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                surface(context, "Update installed ✓", "Cloud SuperApp installed successfully.")
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
                surface(context, "Update failed: $label", message.ifEmpty { label })
            }
        }
    }

    private fun surface(context: Context, short: String, full: String) {
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
