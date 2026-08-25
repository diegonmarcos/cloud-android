package com.diegonmarcos.superapp.notificationcenter

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Captures every system notification the user grants us access to and
 * funnels them into [PhoneNotificationStore]. The store is then read
 * back by the Infos/Admin "Phone Notifications" panel.
 *
 * Wiring:
 *   • Manifest declares this service with BIND_NOTIFICATION_LISTENER_SERVICE.
 *   • User opens Settings → Special access → Notification access and
 *     toggles us on (the panel CTA fires that intent directly).
 *   • Android then calls [onListenerConnected] and starts delivering
 *     onNotificationPosted callbacks. On connect we also seed the store
 *     with [getActiveNotifications] so the panel isn't empty on first
 *     grant.
 *
 * Filtered out: our own SuperApp's notifications (already shown in the
 * in-app Notifications panel right above this one) and notifications
 * with empty title AND text (those are usually FLAG_FOREGROUND_SERVICE
 * ongoing service notifications with no displayable body — Bluetooth
 * connected, VPN active, etc.).
 */
class PhoneNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            for (sbn in activeNotifications.orEmpty()) record(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) record(sbn)
    }

    private fun record(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text  = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val appLabel = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        PhoneNotificationStore.push(this, PhoneNotificationStore.Entry(
            key         = sbn.key,
            ts          = sbn.postTime,
            packageName = pkg,
            appLabel    = appLabel,
            title       = title,
            text        = text,
        ))
    }
}
