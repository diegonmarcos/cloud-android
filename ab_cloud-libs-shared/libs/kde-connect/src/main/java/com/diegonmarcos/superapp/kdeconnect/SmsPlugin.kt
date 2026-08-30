package com.diegonmarcos.superapp.kdeconnect

import android.Manifest
import android.content.Context
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
        val body = packet.getString("messageBody").ifBlank { packet.getString("sendSms") }
        val addr = packet.body.optJSONArray("addresses")?.optJSONObject(0)?.optString("address").orEmpty()
        sendDirect(ctx, addr, body)
    }

    /** Send an SMS straight from this phone (used by the desktop's send request
     *  AND the Send SMS card). Gated on SEND_SMS — returns false if not granted
     *  or the inputs are blank. */
    fun sendDirect(ctx: Context, address: String, body: String): Boolean {
        if (!granted(ctx, Manifest.permission.SEND_SMS)) return false
        if (address.isBlank() || body.isBlank()) return false
        return runCatching {
            @Suppress("DEPRECATION") val sm = android.telephony.SmsManager.getDefault()
            sm.sendTextMessage(address, null, body, null, null); true
        }.getOrDefault(false)
    }
}
