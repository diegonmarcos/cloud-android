package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
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

            // MediaStyle: 5 actions total, first 3 shown in the compact view.
            val n: Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_cloud)
                .setContentTitle("Cloud SA - KDE · $summary")
                .setContentText(line)
                .setSubText(sub)
                .setContentIntent(sharePi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup("nc_kde")
                .addAction(android.R.drawable.ic_menu_share, "Share", sharePi)
                .addAction(android.R.drawable.ic_menu_view, "Desktop", KdeQuickActionReceiver.pi(ctx, "desktop"))
                .addAction(android.R.drawable.ic_popup_sync, "Ping", KdeQuickActionReceiver.pi(ctx, "ping"))
                .addAction(android.R.drawable.stat_notify_sync, "Connect", KdeQuickActionReceiver.pi(ctx, "connect"))
                .addAction(android.R.drawable.ic_menu_compass, "Find", KdeQuickActionReceiver.pi(ctx, "find"))
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken(ctx))
                    .setShowActionsInCompactView(0, 1, 2))
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    // A lightweight (inactive) media session — its token gives MediaStyle the
    // full media-template layout (3 compact + 5 expanded action icons).
    private var session: MediaSessionCompat? = null
    private fun sessionToken(ctx: Context): MediaSessionCompat.Token {
        session?.let { return it.sessionToken }
        val s = MediaSessionCompat(ctx, "CloudSA-KDE").apply {
            setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Cloud SA - KDE")
                .build())
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f).build())
        }
        session = s
        return s.sessionToken
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
