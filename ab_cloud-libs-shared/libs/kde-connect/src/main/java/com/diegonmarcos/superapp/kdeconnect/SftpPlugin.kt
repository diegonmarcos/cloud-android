package com.diegonmarcos.superapp.kdeconnect

import android.content.Context

/** kdeconnect.sftp — two roles. As a RECEIVER the desktop would mount our
 *  filesystem (we can't run an SFTP server, so we ack). As a SENDER (the phone
 *  asks the desktop to expose ITS filesystem) we emit sftp.request and notify;
 *  a full SFTP client UI is future work. */
object SftpPlugin : KdePlugin {
    override val id = "sftp"
    override val incoming = setOf("kdeconnect.sftp.request", "kdeconnect.sftp")
    override val outgoing = setOf("kdeconnect.sftp", "kdeconnect.sftp.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        if (packet.type == "kdeconnect.sftp") return true   // desktop's mount info — ignore (no client yet)
        link.send(NetworkPacket.of("kdeconnect.sftp") {
            put("errorMessage", "SFTP server not supported on this client yet")
        })
        return true
    }
    /** Ask the desktop to start its SFTP server so we could browse it. */
    fun request() = NetworkPacket.of("kdeconnect.sftp.request") { put("startBrowsing", true) }
}
