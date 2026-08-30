package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** kdeconnect.findmyphone.request — ring + vibrate so the user can locate it. */
object FindMyPhonePlugin : KdePlugin {
    override val id = "findmyphone"
    override val incoming = setOf(NetworkPacket.TYPE_FINDMYPHONE, "kdeconnect.findthisdevice")
    override val outgoing = setOf(NetworkPacket.TYPE_FINDMYPHONE)   // we can ring the desktop too
    /** Ask the paired device to ring (find-this-device). */
    fun ring() = NetworkPacket.of(NetworkPacket.TYPE_FINDMYPHONE) {}
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(ctx, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
                }
                play()
            }
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(1500, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        KdeNotifications.post(ctx, "Find My Phone", "Ring requested by ${link.peerName}")
        return true
    }
}
