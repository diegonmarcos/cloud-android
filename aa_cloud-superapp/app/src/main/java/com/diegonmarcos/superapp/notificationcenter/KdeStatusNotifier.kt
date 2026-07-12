package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.kdeconnect.KdeIdentity
import com.diegonmarcos.superapp.kdeconnect.KdePluginPrefs

/**
 * Builds the persistent "Cloud SA - KDE" status notification — the ongoing
 * shade entry that mirrors the in-app KDE badge. Its persistence is owned by
 * [KdeStatusService] (a foreground service, exactly like "Cloud SA - Quick
 * Actions" / FloatingNavService): the service calls [build] for startForeground
 * and re-posts it on every [KdeConnectManager.statusObserver] change. This
 * object is now a pure notification FACTORY + channel manager — no lifecycle.
 */
object KdeStatusNotifier {
    const val CHANNEL_ID = "kde_status"

    /** Build the current "Cloud SA - KDE" notification from live KDE state. */
    fun build(ctx: Context): Notification {
        val cfg = KdeConnectConfig.get()
        val prefs = KdePluginPrefs(ctx)
        val on = cfg.plugins.count { prefs.isEnabled(it.id) }

        val anyConnected = cfg.devices.any { it.id.isNotBlank() && KdeConnectManager.isConnected(it.id) }
        val anyPaired = cfg.devices.any { it.id.isNotBlank() && KdeConnectManager.isPaired(it.id) }
        val summary = when {
            anyConnected -> "Connected"
            anyPaired -> "Paired · tap KDE page to connect"
            else -> "Offline"
        }

        // MediaStyle has no big-text expand → keep it concise: one device
        // line + a sub-text with the tally and identity.
        val dev = cfg.devices.firstOrNull()
        val line = if (dev != null) {
            val connected = dev.id.isNotBlank() && KdeConnectManager.isConnected(dev.id)
            val paired = dev.id.isNotBlank() && KdeConnectManager.isPaired(dev.id)
            "${dev.label} · ${if (connected) "Connected" else "Offline"} · ${if (paired) "Paired" else "Unpaired"}"
        } else "No device declared"
        val sub = "$on/${cfg.plugins.size} plugins · ${KdeConnectManager.pairedDeviceIds().size} paired · " +
            "${KdeIdentity.deviceName(ctx)} ${KdeConnectManager.ownDeviceId().take(10)}…"

        // Share opens the in-app KDE page (its Share card has a text box) —
        // MediaStyle action buttons can't host an inline reply input.
        val sharePi = PendingIntent.getActivity(
            ctx, 0x4B4445,
            Intent(ctx, MainActivity::class.java)
                .putExtra("shortcut_action", "page:config/kde")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // MediaStyle: 5 actions total, first 3 shown in the compact view. Icons
        // are the same fine thin-line One-UI set as "Cloud SA - Quick Actions".
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Cloud SA - KDE · $summary")
            .setContentText(line)
            .setSubText(sub)
            .setContentIntent(sharePi)
            .setDeleteIntent(KdeStatusService.renotifyPi(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setGroup("nc_kde")
            .addAction(R.drawable.ic_kde_share, "Share", sharePi)
            .addAction(R.drawable.ic_kde_desktop, "Desktop", KdeQuickActionReceiver.pi(ctx, "desktop"))
            .addAction(R.drawable.ic_kde_ping, "Ping", KdeQuickActionReceiver.pi(ctx, "ping"))
            .addAction(R.drawable.ic_kde_connect, "Connect", KdeQuickActionReceiver.pi(ctx, "connect"))
            .addAction(R.drawable.ic_kde_find, "Find", KdeQuickActionReceiver.pi(ctx, "find"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Same layout as "Cloud SA - Quick Actions": MediaStyle with NO
            // media-session token → clean action-button row (3 compact / 5
            // expanded), not the media-player chrome.
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
            .build()
            .apply {
                // Reinforce persistence (parity with FloatingNavService): block
                // swipe-to-dismiss + "clear all".
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "KDE Connect", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Cloud SA - KDE connection + pairing status" })
        }
    }
}
