package com.diegonmarcos.superapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.kdeconnect.KdeIdentity
import com.diegonmarcos.superapp.kdeconnect.KdePluginPrefs

/**
 * Persistent "Cloud SA - KDE" status notification — the ongoing shade entry that
 * mirrors the in-app KDE badge, kept in sync live via
 * [KdeConnectManager.statusObserver] (independent of whichever fragment is on
 * screen). Like the other "Cloud SA -" ongoing notifications (Quick Actions,
 * Alerts, Media), it stays put and refreshes in place.
 */
object KdeStatusNotifier {
    private const val CHANNEL_ID = "kde_status"
    private const val NOTIF_ID = 7711
    private var appCtx: Context? = null

    /** Wire once at app start: subscribe to KDE state changes + post the badge. */
    fun init(ctx: Context) {
        if (appCtx != null) { refresh(); return }
        val app = ctx.applicationContext
        appCtx = app
        ensureChannel(app)
        KdeConnectManager.init(app)
        KdeConnectManager.statusObserver = { _, _, _, _ -> refresh() }
        refresh()
    }

    /** Recompute the summary from live state and (re)post the ongoing entry. */
    fun refresh() {
        val ctx = appCtx ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
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

            val big = StringBuilder()
            for (dev in cfg.devices) {
                val connected = dev.id.isNotBlank() && KdeConnectManager.isConnected(dev.id)
                val paired = dev.id.isNotBlank() && KdeConnectManager.isPaired(dev.id)
                val cMark = if (connected) "🔗" else "▫"
                val pMark = if (paired) "🔒" else "▫"
                big.append("$cMark $pMark ${dev.label} — ")
                    .append(if (connected) "Connected" else "Not connected").append(" · ")
                    .append(if (paired) "Paired" else "Not paired").append('\n')
            }
            big.append("🔌 $on/${cfg.plugins.size} plugins enabled · ")
                .append("${KdeConnectManager.pairedDeviceIds().size} paired").append('\n')
            // Extra info (we have the room in BigText).
            big.append("🆔 ${KdeIdentity.deviceName(ctx)} · ${KdeConnectManager.ownDeviceId().take(14)}…")

            val n: Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_cloud)
                .setContentTitle("Cloud SA - KDE")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup("nc_kde")
                // Quick actions (Android shows the first ~3 collapsed, all expanded).
                .addAction(android.R.drawable.ic_menu_share, "Share",
                    KdeQuickActionReceiver.pi(ctx, "share"))
                .addAction(android.R.drawable.ic_menu_view, "Desktop",
                    KdeQuickActionReceiver.pi(ctx, "desktop"))
                .addAction(android.R.drawable.ic_popup_sync, "Ping",
                    KdeQuickActionReceiver.pi(ctx, "ping"))
                .addAction(android.R.drawable.stat_notify_sync, "Connect",
                    KdeQuickActionReceiver.pi(ctx, "connect"))
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "KDE Connect", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Cloud SA - KDE connection + pairing status" })
        }
    }
}
