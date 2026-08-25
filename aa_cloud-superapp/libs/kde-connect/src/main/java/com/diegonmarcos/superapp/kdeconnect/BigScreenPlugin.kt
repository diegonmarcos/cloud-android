package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.bigscreen.stt — push a line of text (or dictated speech) to a
 *  Plasma Big Screen. */
object BigScreenPlugin : KdePlugin {
    override val id = "bigscreen"
    override val incoming = emptySet<String>()
    override val outgoing = setOf("kdeconnect.bigscreen.stt")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket) = true
    fun stt(content: String) = NetworkPacket.of("kdeconnect.bigscreen.stt") { put("content", content) }
}
