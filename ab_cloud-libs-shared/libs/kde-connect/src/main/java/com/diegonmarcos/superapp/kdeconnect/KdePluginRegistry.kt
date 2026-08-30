package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

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
