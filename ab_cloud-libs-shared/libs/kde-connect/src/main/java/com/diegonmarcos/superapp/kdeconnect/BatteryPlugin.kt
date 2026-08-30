package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

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
                    ctx, "Cloud SA - KDE/${link.peerName} Battery",
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
