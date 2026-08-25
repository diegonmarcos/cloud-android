package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.remotedesktop — Plasma-6 remote desktop (screen + input). Full
 *  streaming isn't implemented; we acknowledge inbound and can request a start. */
object RemoteDesktopPlugin : KdePlugin {
    override val id = "screenconnector"
    override val incoming = setOf("kdeconnect.remotedesktop", "kdeconnect.remotedesktop.request")
    override val outgoing = setOf("kdeconnect.remotedesktop.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket) = true
    /** Ask the desktop to begin a remote-desktop session. */
    fun request() = NetworkPacket.of("kdeconnect.remotedesktop.request") { put("requestSession", true) }
    /** Ask the desktop to end the remote-desktop session. */
    fun stop() = NetworkPacket.of("kdeconnect.remotedesktop.request") { put("stopSession", true) }
}
