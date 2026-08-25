package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager

/**
 * Foreground service that OWNS the persistent "Cloud SA - KDE" notification —
 * the exact same persistence mechanism as "Cloud SA - Quick Actions"
 * (FloatingNavService). A plain `nm.notify(ongoing)` (the previous approach in
 * App.onCreate) is not tied to a living process, so the system drops it on
 * "clear all" / process death / reboot. Holding it via [startForeground] keeps
 * it pinned for the service's lifetime.
 *
 * The notification body is built by [KdeStatusNotifier.build]; the service
 * re-posts it on every [KdeConnectManager.statusObserver] change so the badge
 * tracks connect/pair state live, independent of any on-screen fragment.
 */
class KdeStatusService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        KdeStatusNotifier.ensureChannel(this)
        KdeConnectManager.init(this)
        // Live badge: any KDE state change → re-post the foreground notification.
        KdeConnectManager.statusObserver = { _, _, _, _ -> repost() }
        startForeground(NOTIF_ID, KdeStatusNotifier.build(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Both ACTION_REFRESH (state changed) and ACTION_RENOTIFY (user swiped
        // it away on Android 14+) just rebuild + re-post in place.
        repost()
        return START_STICKY
    }

    private fun repost() {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, KdeStatusNotifier.build(this))
        }
    }

    companion object {
        const val NOTIF_ID = 7711
        private const val ACTION_REFRESH = "com.diegonmarcos.superapp.kde.REFRESH"
        private const val ACTION_RENOTIFY = "com.diegonmarcos.superapp.kde.RENOTIFY"

        /** Start (idempotent) the foreground service that pins the KDE badge. */
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            ContextCompat.startForegroundService(app, Intent(app, KdeStatusService::class.java))
        }

        /** Ask the running service to rebuild + re-post the badge from live state. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            ContextCompat.startForegroundService(
                app, Intent(app, KdeStatusService::class.java).setAction(ACTION_REFRESH))
        }

        /** Delete-intent: re-post the badge if the user swipes it away (Android
         *  14+ allows dismissing FGS notifications). The service is alive, so a
         *  getService PendingIntent is a permitted same-process re-entry. */
        fun renotifyPi(ctx: Context): PendingIntent {
            val app = ctx.applicationContext
            return PendingIntent.getService(
                app, 0x4B44_4E54,
                Intent(app, KdeStatusService::class.java).setAction(ACTION_RENOTIFY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
