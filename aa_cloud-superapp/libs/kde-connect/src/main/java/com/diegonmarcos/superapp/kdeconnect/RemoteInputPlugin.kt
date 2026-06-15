package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/**
 * Phone-as-controller senders. These advertise OUTGOING packets so the desktop
 * accepts our input; the actual triggering is the remote-control UI on the KDE
 * page (touchpad / keyboard field / big-screen field). No inbound handling.
 */

/** kdeconnect.mousepad.request — the phone touchpad/keyboard drives the desktop
 *  cursor + types into it. This is "Remote Desktop Control" + "Remote Keyboard". */
object RemoteInputPlugin : KdePlugin {
    private const val PKT = "kdeconnect.mousepad.request"
    override val id = "mousepad"
    override val incoming = emptySet<String>()
    override val outgoing = setOf(PKT)
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket) = true

    fun move(dx: Float, dy: Float) = NetworkPacket.of(PKT) { put("dx", dx.toDouble()); put("dy", dy.toDouble()) }
    fun click() = NetworkPacket.of(PKT) { put("singleclick", true) }
    fun rightClick() = NetworkPacket.of(PKT) { put("rightclick", true) }
    fun scroll(dx: Float, dy: Float) = NetworkPacket.of(PKT) {
        put("scroll", true); put("dx", dx.toDouble()); put("dy", dy.toDouble())
    }
    fun key(text: String) = NetworkPacket.of(PKT) { put("key", text) }
    /** KDE special keys: 1=Backspace, 12=Return, 13=Esc … (KeyListenerView map). */
    fun specialKey(code: Int) = NetworkPacket.of(PKT) { put("specialKey", code) }
}
