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
    /** Catalog id (build.json plugins[].id) — the per-plugin enable/disable key. */
    val id: String
    val incoming: Set<String>
    val outgoing: Set<String>
    /** Handle a packet whose type is in [incoming]. Returns true if consumed. */
    fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean
    /** Called once a link is established + paired — for plugins that PUSH state
     *  proactively (e.g. battery reports its level on connect). Default no-op. */
    fun onLinkReady(ctx: Context, link: KdeLink) {}
}

/** Static registry — the set of plugins the app supports. Capability lists in
 *  our identity packet are derived from this, so adding a plugin here makes us
 *  advertise it automatically. */
object KdePluginRegistry {
    val plugins: List<KdePlugin> = listOf(
        PingPlugin, ClipboardPlugin, FindMyPhonePlugin, NotificationMirrorPlugin,
        BatteryPlugin, SharePlugin, MprisPlugin, SystemVolumePlugin, RunCommandPlugin,
        ContactsPlugin, ConnectivityReportPlugin, LockDevicePlugin, TelephonyPlugin,
        RemoteInputPlugin, RemoteKeyboardPlugin, BigScreenPlugin,
        SmsPlugin, PresenterPlugin, PhotoPlugin, SftpPlugin, VirtualMonitorPlugin, RemoteDesktopPlugin,
        RemoteSystemVolumePlugin,
    )
    /** Only the user-enabled plugins (default all). */
    private fun enabled(ctx: Context): List<KdePlugin> {
        val prefs = KdePluginPrefs(ctx)
        return plugins.filter { prefs.isEnabled(it.id) }
    }

    /** Capabilities we ADVERTISE so the desktop shows every enabled plugin:
     *  the union of REAL plugins' packet types and the catalog's packet for
     *  active entries that don't (yet) have a Kotlin handler. Both are gated by
     *  the per-plugin toggle, so disabling one drops it from the desktop on the
     *  next connect. (Stub entries are advertised for visibility/testing; their
     *  inbound packets are simply ignored until a handler lands.) */
    fun incomingCapabilities(ctx: Context): Set<String> = caps(ctx, outgoingDir = false)
    fun outgoingCapabilities(ctx: Context): Set<String> = caps(ctx, outgoingDir = true)

    private fun caps(ctx: Context, outgoingDir: Boolean): Set<String> {
        val prefs = KdePluginPrefs(ctx)
        val realIds = plugins.map { it.id }.toSet()
        val real = enabled(ctx).flatMap { if (outgoingDir) it.outgoing else it.incoming }
        val excludedDir = if (outgoingDir) "in" else "out"
        val stub = KdeConnectConfig.get().plugins
            .filter { it.active && it.id !in realIds && it.dir != excludedDir && prefs.isEnabled(it.id) }
            .map { it.packet }
        return (real + stub).toSet()
    }

    /** Dispatch an inbound packet to the first ENABLED plugin that owns its type. */
    fun dispatch(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean =
        enabled(ctx).firstOrNull { packet.type in it.incoming }
            ?.onPacket(ctx, link, packet) ?: false

    /** Notify every enabled plugin that a paired link is ready. */
    fun linkReady(ctx: Context, link: KdeLink) =
        enabled(ctx).forEach { runCatching { it.onLinkReady(ctx, link) } }
}

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

/** kdeconnect.clipboard — apply the peer's clipboard locally. (Android 10+
 *  restricts background clipboard writes; best-effort while foregrounded.) */
object ClipboardPlugin : KdePlugin {
    override val id = "clipboard"
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
    override val id = "findmyphone"
    override val incoming = setOf(NetworkPacket.TYPE_FINDMYPHONE, "kdeconnect.findthisdevice")
    override val outgoing = setOf(NetworkPacket.TYPE_FINDMYPHONE)   // we can ring the desktop too
    /** Ask the paired device to ring (find-this-device). */
    fun ring() = NetworkPacket.of(NetworkPacket.TYPE_FINDMYPHONE) {}
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

/** kdeconnect.battery — report the phone's charge level to the desktop (on
 *  connect + on request), and surface the desktop's level back. */
object BatteryPlugin : KdePlugin {
    override val id = "battery"
    override val incoming = setOf(NetworkPacket.TYPE_BATTERY, NetworkPacket.TYPE_BATTERY_REQUEST)
    override val outgoing = setOf(NetworkPacket.TYPE_BATTERY, NetworkPacket.TYPE_BATTERY_REQUEST)
    override fun onLinkReady(ctx: Context, link: KdeLink) { link.send(report(ctx)) }
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        when (packet.type) {
            NetworkPacket.TYPE_BATTERY_REQUEST -> link.send(report(ctx))
            NetworkPacket.TYPE_BATTERY ->
                if (packet.has("currentCharge")) KdeNotifications.post(
                    ctx, "${link.peerName} battery",
                    "${packet.getInt("currentCharge")}%" +
                        if (packet.getBoolean("isCharging")) " · charging" else "")
        }
        return true
    }
    private fun report(ctx: Context): NetworkPacket {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return NetworkPacket.of(NetworkPacket.TYPE_BATTERY) {
            put("currentCharge", pct)
            put("isCharging", charging)
            put("thresholdEvent", if (pct in 1..15 && !charging) 1 else 0)
        }
    }
}

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
}

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
