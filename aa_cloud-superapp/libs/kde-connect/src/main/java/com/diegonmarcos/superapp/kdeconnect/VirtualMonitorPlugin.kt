package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.virtualmonitor — the desktop offers a virtual monitor stream;
 *  displaying it needs a video decoder + surface we don't have yet. As a sender
 *  we can still ASK the desktop to start one. */
object VirtualMonitorPlugin : KdePlugin {
    override val id = "virtualmonitor"
    override val incoming = setOf("kdeconnect.virtualmonitor.request", "kdeconnect.virtualmonitor")
    override val outgoing = setOf("kdeconnect.virtualmonitor.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        if (packet.type == "kdeconnect.virtualmonitor") return true
        link.send(NetworkPacket.of("kdeconnect.virtualmonitor.request") {
            put("failed", "Virtual monitor display not supported on this client yet")
        })
        return true
    }
    /** Ask the desktop to start a virtual monitor (resolution hint). */
    fun request() = NetworkPacket.of("kdeconnect.virtualmonitor.request") {
        put("resolution", "1920x1080"); put("scale", 1.0)
    }
    /** Tell the desktop to tear the virtual monitor down (it stops on receiving
     *  a plain kdeconnect.virtualmonitor — see virtualmonitorplugin.cpp). */
    fun stop() = NetworkPacket.of("kdeconnect.virtualmonitor") { put("stop", true) }
}
