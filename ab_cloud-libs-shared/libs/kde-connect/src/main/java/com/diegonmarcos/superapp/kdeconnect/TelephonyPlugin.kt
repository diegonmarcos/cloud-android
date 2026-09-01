package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.media.AudioManager

/**
 * kdeconnect.telephony — the desktop can mute the phone's ringer for an
 * incoming call (request_mute). Best-effort: silencing the ringer needs DND /
 * notification-policy access on newer Android; if denied it's a no-op.
 */
object TelephonyPlugin : KdePlugin {
    override val id = "telephony"
    override val incoming = setOf("kdeconnect.telephony.request_mute")
    override val outgoing = setOf("kdeconnect.telephony")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
        }
        return true
    }
}
