package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.notification — mirror a desktop notification onto the phone. */
object NotificationMirrorPlugin : KdePlugin {
    override val id = "notification"
    override val incoming = setOf(NetworkPacket.TYPE_NOTIFICATION)
    override val outgoing = emptySet<String>()
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        if (packet.getBoolean("isCancel")) return true   // dismissal, ignore for now
        val appName = packet.getString("appName", link.peerName)
        val title = packet.getString("title")
        val text = packet.getString("text", packet.getString("ticker"))
        val key = packet.getString("id").ifBlank { "${link.peerDeviceId}:$appName" }
        KdeNotifications.post(ctx, listOf(appName, title).filter { it.isNotBlank() }.joinToString(" · "),
            text, key = key)
        return true
    }
}
