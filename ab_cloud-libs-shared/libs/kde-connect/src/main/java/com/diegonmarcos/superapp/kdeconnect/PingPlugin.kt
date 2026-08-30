package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.ping — surface a notification; we can also send one. */
object PingPlugin : KdePlugin {
    override val id = "ping"
    override val incoming = setOf(NetworkPacket.TYPE_PING)
    override val outgoing = setOf(NetworkPacket.TYPE_PING)
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        val msg = packet.getString("message", "Ping")
        KdeNotifications.post(ctx, "Ping · ${link.peerName}", msg)
        return true
    }
    fun build(message: String? = null) = NetworkPacket.of(NetworkPacket.TYPE_PING) {
        if (message != null) put("message", message)
    }
}
