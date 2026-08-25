package com.diegonmarcos.superapp.kdeconnect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** KDE-Connect notification surface — one channel, simple posts. Used by the
 *  Ping and Notification-mirror plugins. POST_NOTIFICATIONS (API 33+) is
 *  requested by the host app; if absent the post is silently a no-op. */
object KdeNotifications {
    const val CHANNEL_ID = "kdeconnect"
    private var nextId = 7100

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "KDE Connect", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Pings and mirrored notifications from paired devices." }
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    @Synchronized
    fun post(ctx: Context, title: String, text: String, key: String? = null) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching {
            // key → stable id so repeated updates of the same remote
            // notification replace rather than stack.
            val id = key?.hashCode() ?: nextId++
            NotificationManagerCompat.from(ctx).notify(id, n)
        }
    }
}
