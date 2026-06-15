package com.diegonmarcos.superapp.kdeconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** kdeconnect.share.request — receive a shared URL (open it) or text (copy +
 *  notify). File payloads use KDE's separate transfer channel — not handled. */
object SharePlugin : KdePlugin {
    override val id = "share"
    override val incoming = setOf(NetworkPacket.TYPE_SHARE)
    override val outgoing = setOf(NetworkPacket.TYPE_SHARE)
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        when {
            packet.getString("url").isNotEmpty() -> runCatching {
                ctx.startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(packet.getString("url")),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            packet.getString("text").isNotEmpty() -> {
                val text = packet.getString("text")
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("KDE Connect", text))
                KdeNotifications.post(ctx, "Shared text · ${link.peerName}",
                    text.take(120) + if (text.length > 120) "…" else "")
            }
            packet.has("filename") -> KdeNotifications.post(ctx, "Incoming file · ${link.peerName}",
                "${packet.getString("filename")} (file transfer not yet supported)")
        }
        return true
    }
    /** Sender builders — push text / a link to the desktop. */
    fun text(value: String) = NetworkPacket.of(NetworkPacket.TYPE_SHARE) { put("text", value) }
    fun url(value: String) = NetworkPacket.of(NetworkPacket.TYPE_SHARE) { put("url", value) }
}
