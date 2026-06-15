package com.diegonmarcos.superapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.kdeconnect.KdeIdentity
import com.diegonmarcos.superapp.kdeconnect.RemoteDesktopPlugin
import com.diegonmarcos.superapp.kdeconnect.SharePlugin

/**
 * Handles the quick-action buttons on the persistent "Cloud SA - KDE"
 * notification. Each action targets the first live link (or kicks a connect if
 * none); all sends go through KdeConnectManager, which writes off the main
 * thread, so this is safe from a BroadcastReceiver. Refreshes the badge after.
 */
class KdeQuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        KdeConnectManager.init(app)
        val id = KdeConnectManager.firstConnectedId()
        when (intent.getStringExtra(EXTRA)) {
            "connect" -> KdeConnectManager.connectFirstAsync()
            "ping" ->
                if (id != null) KdeConnectManager.sendPing(id, "Ping from ${KdeIdentity.deviceName(app)}")
                else KdeConnectManager.connectFirstAsync()
            "desktop" ->
                if (id != null) KdeConnectManager.send(id, RemoteDesktopPlugin.request())
                else KdeConnectManager.connectFirstAsync()
            "share" -> {
                val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
                when {
                    id == null -> KdeConnectManager.connectFirstAsync()
                    clip.isNotBlank() -> KdeConnectManager.send(id, SharePlugin.text(clip))
                }
            }
        }
        KdeStatusNotifier.refresh()
    }

    companion object {
        const val EXTRA = "kde_action"
        /** A broadcast PendingIntent for one quick action (stable per action). */
        fun pi(ctx: Context, action: String): PendingIntent {
            val intent = Intent(ctx, KdeQuickActionReceiver::class.java)
                .setAction("com.diegonmarcos.superapp.KDE_$action")
                .putExtra(EXTRA, action)
            return PendingIntent.getBroadcast(
                ctx, action.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
