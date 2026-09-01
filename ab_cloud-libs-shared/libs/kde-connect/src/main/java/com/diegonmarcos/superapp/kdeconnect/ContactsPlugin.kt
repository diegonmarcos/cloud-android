package com.diegonmarcos.superapp.kdeconnect

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray

/**
 * kdeconnect.contacts — answer the desktop's contact-sync requests with the
 * phone's contacts (uids + timestamps, then vCards on demand). Gated on
 * READ_CONTACTS: until the host app grants it we reply EMPTY so the desktop
 * never hangs waiting.
 */
object ContactsPlugin : KdePlugin {
    private const val REQ_UIDS   = "kdeconnect.contacts.request_all_uids_timestamps"
    private const val REQ_VCARDS = "kdeconnect.contacts.request_vcards_by_uid"
    private const val RES_UIDS   = "kdeconnect.contacts.response_uids_timestamps"
    private const val RES_VCARDS = "kdeconnect.contacts.response_vcards"

    override val id = "contacts"
    override val incoming = setOf(REQ_UIDS, REQ_VCARDS)
    override val outgoing = setOf(RES_UIDS, RES_VCARDS)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        val ok = granted(ctx, Manifest.permission.READ_CONTACTS)
        when (packet.type) {
            REQ_UIDS   -> link.send(if (ok) uidsPacket(ctx)
                          else NetworkPacket.of(RES_UIDS) { put("uids", JSONArray()) })
            REQ_VCARDS -> if (ok) link.send(vcardsPacket(ctx, packet.body.optJSONArray("uids")))
        }
        return true
    }

    private fun uidsPacket(ctx: Context): NetworkPacket {
        val pkt = NetworkPacket.of(RES_UIDS) {}
        val uids = JSONArray()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null, null, null,
            )?.use { c ->
                val idIx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val tsIx = c.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                while (c.moveToNext()) {
                    val uid = c.getString(idIx)
                    uids.put(uid)
                    pkt.body.put(uid, if (tsIx >= 0) c.getLong(tsIx) else 0L)
                }
            }
        }
        pkt.body.put("uids", uids)
        return pkt
    }

    private fun vcardsPacket(ctx: Context, requested: JSONArray?): NetworkPacket {
        val pkt = NetworkPacket.of(RES_VCARDS) {}
        val uids = JSONArray()
        if (requested != null) for (i in 0 until requested.length()) {
            val uid = requested.optString(i)
            if (uid.isBlank()) continue
            runCatching { vcard(ctx, uid) }.getOrNull()?.let {
                uids.put(uid); pkt.body.put(uid, it)
            }
        }
        pkt.body.put("uids", uids)
        return pkt
    }

    /** Minimal vCard 3.0 (FN + TEL + EMAIL) for one contact uid. */
    private fun vcard(ctx: Context, uid: String): String {
        val sb = StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID}=?", arrayOf(uid), null,
        )?.use { c -> if (c.moveToFirst()) sb.append("FN:").append(c.getString(0).orEmpty()).append("\r\n") }
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(uid), null,
        )?.use { c -> while (c.moveToNext()) sb.append("TEL;TYPE=CELL:").append(c.getString(0).orEmpty()).append("\r\n") }
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?", arrayOf(uid), null,
        )?.use { c -> while (c.moveToNext()) sb.append("EMAIL:").append(c.getString(0).orEmpty()).append("\r\n") }
        sb.append("END:VCARD\r\n")
        return sb.toString()
    }
}
