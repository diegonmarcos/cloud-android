package com.diegonmarcos.superapp.kdeconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * A KDE-Connect plugin: declares which packet [incoming] types it handles and
 * which [outgoing] types it may emit (these feed our identity's capability
 * lists), and processes inbound packets it owns. Original implementation of
 * the documented packet contracts.
 */
interface KdePlugin {
    val incoming: Set<String>
    val outgoing: Set<String>
    /** Handle a packet whose type is in [incoming]. Returns true if consumed. */
    fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean
}

/** Static registry — the set of plugins the app supports. Capability lists in
 *  our identity packet are derived from this, so adding a plugin here makes us
 *  advertise it automatically. */
object KdePluginRegistry {
    val plugins: List<KdePlugin> = listOf(
        PingPlugin, ClipboardPlugin, FindMyPhonePlugin, NotificationMirrorPlugin,
    )
    val incomingCapabilities: Set<String> = plugins.flatMap { it.incoming }.toSet()
    val outgoingCapabilities: Set<String> = plugins.flatMap { it.outgoing }.toSet()

    /** Dispatch an inbound packet to the first plugin that owns its type. */
    fun dispatch(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean =
        plugins.firstOrNull { packet.type in it.incoming }
            ?.onPacket(ctx, link, packet) ?: false
}

/** kdeconnect.ping — surface a notification; we can also send one. */
object PingPlugin : KdePlugin {
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

/** kdeconnect.clipboard — apply the peer's clipboard locally. (Android 10+
 *  restricts background clipboard writes; best-effort while foregrounded.) */
object ClipboardPlugin : KdePlugin {
    override val incoming = setOf(NetworkPacket.TYPE_CLIPBOARD, NetworkPacket.TYPE_CLIPBOARD_CONNECT)
    override val outgoing = setOf(NetworkPacket.TYPE_CLIPBOARD)
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        val content = packet.getString("content")
        if (content.isEmpty()) return true
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("KDE Connect", content))
        }.onFailure { Log.i("KdeConnect/Clip", "clipboard write blocked: ${it.message}") }
        return true
    }
    fun build(content: String) = NetworkPacket.of(NetworkPacket.TYPE_CLIPBOARD) { put("content", content) }
}

/** kdeconnect.findmyphone.request — ring + vibrate so the user can locate it. */
object FindMyPhonePlugin : KdePlugin {
    override val incoming = setOf(NetworkPacket.TYPE_FINDMYPHONE)
    override val outgoing = emptySet<String>()
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(ctx, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
                }
                play()
            }
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(1500, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        KdeNotifications.post(ctx, "Find My Phone", "Ring requested by ${link.peerName}")
        return true
    }
}

/** kdeconnect.notification — mirror a desktop notification onto the phone. */
object NotificationMirrorPlugin : KdePlugin {
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
