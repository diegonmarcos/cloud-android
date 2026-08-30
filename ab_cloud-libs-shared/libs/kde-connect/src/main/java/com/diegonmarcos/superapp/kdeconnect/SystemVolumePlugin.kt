package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray

/**
 * kdeconnect.systemvolume — let the desktop read + set the phone's media
 * volume. We expose the music stream as a single sink. Uses AudioManager (no
 * permission). Volume values are raw stream units (0..maxVolume), per KDE.
 */
object SystemVolumePlugin : KdePlugin {
    override val id = "systemvolume"
    override val incoming = setOf(NetworkPacket.TYPE_SYSTEMVOLUME_REQUEST)
    override val outgoing = setOf(NetworkPacket.TYPE_SYSTEMVOLUME)

    override fun onLinkReady(ctx: Context, link: KdeLink) = sendSinks(ctx, link)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            packet.getBoolean("requestSinks") -> sendSinks(ctx, link)
            packet.has("volume") -> runCatching {
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    packet.getInt("volume").coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            }.also { sendSinks(ctx, link) }
            packet.has("muted") -> runCatching {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (packet.getBoolean("muted")) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            }.also { sendSinks(ctx, link) }
        }
        return true
    }

    private fun sendSinks(ctx: Context, link: KdeLink) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val sink = org.json.JSONObject().apply {
            put("name", "media"); put("description", "Media volume")
            put("muted", false); put("volume", cur); put("maxVolume", max)
            put("enabled", true)
        }
        link.send(NetworkPacket.of(NetworkPacket.TYPE_SYSTEMVOLUME) {
            put("sinkList", JSONArray().put(sink))
        })
    }
}
