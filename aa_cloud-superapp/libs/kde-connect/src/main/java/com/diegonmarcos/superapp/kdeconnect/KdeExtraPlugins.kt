package com.diegonmarcos.superapp.kdeconnect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/**
 * The remaining KDE Connect plugins, given real handlers (or honest acknowledge
 * + notify where the full subsystem isn't available on a phone-library client).
 */

/** kdeconnect.sms — answer the desktop's SMS requests from the phone inbox.
 *  request_conversations → latest message per thread; request{threadID} →
 *  that thread. Read gated on READ_SMS (empty reply until granted). Sending a
 *  message (request{sendSms}) uses SmsManager when SEND_SMS is granted. */
object SmsPlugin : KdePlugin {
    private const val REQ_CONV = "kdeconnect.sms.request_conversations"
    private const val REQ      = "kdeconnect.sms.request"
    private const val MESSAGES = "kdeconnect.sms.messages"
    override val id = "sms"
    override val incoming = setOf(REQ_CONV, REQ)
    override val outgoing = setOf(MESSAGES)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        when (packet.type) {
            REQ_CONV -> link.send(messages(ctx, threadId = null, latestPerThread = true))
            REQ -> {
                if (packet.has("sendSms")) sendSms(ctx, packet)
                else link.send(messages(ctx, threadId = packet.body.optLong("threadID", -1L), latestPerThread = false))
            }
        }
        return true
    }

    private fun messages(ctx: Context, threadId: Long?, latestPerThread: Boolean): NetworkPacket {
        val arr = JSONArray()
        if (granted(ctx, Manifest.permission.READ_SMS)) runCatching {
            val proj = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
            val sel = if (threadId != null && threadId >= 0) "${Telephony.Sms.THREAD_ID}=?" else null
            val args = if (sel != null) arrayOf(threadId.toString()) else null
            val seen = HashSet<Long>()
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, proj, sel, args,
                "${Telephony.Sms.DATE} DESC LIMIT 300")?.use { c ->
                while (c.moveToNext() && arr.length() < 200) {
                    val tid = c.getLong(1)
                    if (latestPerThread && !seen.add(tid)) continue
                    arr.put(JSONObject().apply {
                        put("_id", c.getLong(0)); put("thread_id", tid)
                        put("body", c.getString(3).orEmpty()); put("date", c.getLong(4))
                        put("type", c.getInt(5)); put("read", c.getInt(6)); put("sub_id", -1); put("event", 1)
                        put("addresses", JSONArray().put(JSONObject().put("address", c.getString(2).orEmpty())))
                        put("address", c.getString(2).orEmpty())
                    })
                }
            }
        }
        return NetworkPacket.of(MESSAGES) { put("version", 2); put("messages", arr) }
    }

    private fun sendSms(ctx: Context, packet: NetworkPacket) {
        if (!granted(ctx, Manifest.permission.SEND_SMS)) return
        val body = packet.getString("messageBody").ifBlank { packet.getString("sendSms") }
        val addr = packet.body.optJSONArray("addresses")?.optJSONObject(0)?.optString("address").orEmpty()
        if (addr.isBlank() || body.isBlank()) return
        runCatching {
            @Suppress("DEPRECATION") val sm = android.telephony.SmsManager.getDefault()
            sm.sendTextMessage(addr, null, body, null, null)
        }
    }
}

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

/** kdeconnect.virtualmonitor — the desktop offers a virtual monitor stream;
 *  displaying it needs a video decoder + surface we don't have yet. As a sender
 *  we can still ASK the desktop to start one. */
object VirtualMonitorPlugin : KdePlugin {
    override val id = "virtualmonitor"
    override val incoming = setOf("kdeconnect.virtualmonitor.request", "kdeconnect.virtualmonitor")
    override val outgoing = setOf("kdeconnect.virtualmonitor.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        if (packet.type == "kdeconnect.virtualmonitor") return true
        link.send(NetworkPacket.of("kdeconnect.virtualmonitor.request") {
            put("failed", "Virtual monitor display not supported on this client yet")
        })
        return true
    }
    /** Ask the desktop to start a virtual monitor (resolution hint). */
    fun request() = NetworkPacket.of("kdeconnect.virtualmonitor.request") {
        put("resolution", "1920x1080"); put("scale", 1.0)
    }
}

/** kdeconnect.remotedesktop — Plasma-6 remote desktop (screen + input). Full
 *  streaming isn't implemented; we acknowledge inbound and can request a start. */
object RemoteDesktopPlugin : KdePlugin {
    override val id = "screenconnector"
    override val incoming = setOf("kdeconnect.remotedesktop", "kdeconnect.remotedesktop.request")
    override val outgoing = setOf("kdeconnect.remotedesktop.request")
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket) = true
    /** Ask the desktop to begin a remote-desktop session. */
    fun request() = NetworkPacket.of("kdeconnect.remotedesktop.request") { put("requestSession", true) }
}

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
