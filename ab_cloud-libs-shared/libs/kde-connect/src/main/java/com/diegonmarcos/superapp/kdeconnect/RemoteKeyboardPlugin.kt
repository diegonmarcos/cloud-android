package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.remotekeyboard.request — advertise the dedicated remote-keyboard
 *  capability too; key delivery rides the mousepad `key` field above. */
object RemoteKeyboardPlugin : KdePlugin {
    override val id = "remotekeyboard"
    override val incoming = setOf("kdeconnect.remotekeyboard.echo")
    override val outgoing = setOf("kdeconnect.remotekeyboard.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket) = true
}
