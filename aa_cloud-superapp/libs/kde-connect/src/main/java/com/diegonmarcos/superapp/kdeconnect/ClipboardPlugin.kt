package com.diegonmarcos.superapp.kdeconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/** kdeconnect.clipboard — apply the peer's clipboard locally. (Android 10+
 *  restricts background clipboard writes; best-effort while foregrounded.) */
object ClipboardPlugin : KdePlugin {
    override val id = "clipboard"
    override val incoming = setOf(NetworkPacket.TYPE_CLIPBOARD, NetworkPacket.TYPE_CLIPBOARD_CONNECT)
    override val outgoing = setOf(NetworkPacket.TYPE_CLIPBOARD)
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        val content = packet.getString("content")
        if (content.isEmpty()) return true
        // Remember it BEFORE trying to write: the write below is the part
        // Android can refuse, and the Configs > KDE page still has to be able
        // to show what the desktop sent even when it was refused.
        KdeClipboardStore.rememberHost(ctx, content)
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("KDE Connect", content))
        }.onFailure { Log.i("KdeConnect/Clip", "clipboard write blocked: ${it.message}") }
        return true
    }
    fun build(content: String) = NetworkPacket.of(NetworkPacket.TYPE_CLIPBOARD) { put("content", content) }
}
