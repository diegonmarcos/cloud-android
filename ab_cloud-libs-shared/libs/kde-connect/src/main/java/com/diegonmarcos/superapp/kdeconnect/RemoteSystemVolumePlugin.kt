package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** Remote system volume — the phone controls the DESKTOP's master volume. We
 *  receive the desktop's sink list (kdeconnect.systemvolume), cache the primary
 *  sink, and send absolute/mute changes (kdeconnect.systemvolume.request). */
object RemoteSystemVolumePlugin : KdePlugin {
    override val id = "remotesystemvolume"
    override val incoming = setOf("kdeconnect.systemvolume")
    override val outgoing = setOf("kdeconnect.systemvolume.request")

    data class Sink(val name: String, val volume: Int, val maxVolume: Int, val muted: Boolean)
    @Volatile var primary: Sink? = null
        private set

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        val sinks = packet.body.optJSONArray("sinkList") ?: return true
        val s = sinks.optJSONObject(0) ?: return true
        primary = Sink(
            name = s.optString("name"),
            volume = s.optInt("volume"),
            maxVolume = s.optInt("maxVolume", 100).coerceAtLeast(1),
            muted = s.optBoolean("muted"),
        )
        return true
    }

    fun requestSinks() = NetworkPacket.of("kdeconnect.systemvolume.request") { put("requestSinks", true) }
    fun setVolume(raw: Int): NetworkPacket {
        val s = primary
        return NetworkPacket.of("kdeconnect.systemvolume.request") {
            put("name", s?.name ?: "")
            put("volume", if (s != null) raw.coerceIn(0, s.maxVolume) else raw)
        }
    }
    fun mute(muted: Boolean) = NetworkPacket.of("kdeconnect.systemvolume.request") {
        put("name", primary?.name ?: ""); put("muted", muted)
    }
    /** Nudge the desktop volume by ±step% of its max, from the cached level. */
    fun nudge(deltaPct: Int): NetworkPacket? {
        val s = primary ?: return null
        val step = (s.maxVolume * deltaPct) / 100
        return setVolume(s.volume + step)
    }
}
