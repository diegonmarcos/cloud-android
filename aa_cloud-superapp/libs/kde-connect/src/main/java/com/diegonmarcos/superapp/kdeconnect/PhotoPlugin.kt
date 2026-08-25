package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/** kdeconnect.photo.request — the desktop asks for a photo; launch the camera
 *  (returning the captured file needs the payload-transfer channel, which this
 *  client doesn't run yet — so we capture but don't upload). */
object PhotoPlugin : KdePlugin {
    override val id = "photo"
    override val incoming = setOf("kdeconnect.photo.request")
    override val outgoing = setOf("kdeconnect.photo")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        capture(ctx)
        KdeNotifications.post(ctx, "Photo requested · ${link.peerName}",
            "Camera opened. (Sending the file to the desktop needs the transfer channel — coming later.)")
        return true
    }
    /** Open the camera to capture a photo (used by the desktop request + the
     *  Photo capture card). Upload to the desktop needs the transfer channel. */
    fun capture(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
