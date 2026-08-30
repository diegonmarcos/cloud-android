package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.presenter — phone acts as a slideshow pointer; we advertise the
 *  sender + provide build helpers (a future presenter UI drives it). */
object PresenterPlugin : KdePlugin {
    override val id = "presenter"
    override val incoming = emptySet<String>()
    override val outgoing = setOf("kdeconnect.presenter", "kdeconnect.presenter.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket) = true
    fun pointer(dx: Float, dy: Float) = NetworkPacket.of("kdeconnect.presenter") {
        put("dx", dx.toDouble()); put("dy", dy.toDouble())
    }
    fun stop() = NetworkPacket.of("kdeconnect.presenter") { put("stop", true) }
}
