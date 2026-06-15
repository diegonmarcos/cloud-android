package com.diegonmarcos.superapp.kdeconnect

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

private fun granted(ctx: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

/**
 * kdeconnect.runcommand — expose a data-driven list of commands the desktop can
 * trigger on the phone (build.json commands[]): launch an app or open a URL. No
 * permission required. The commandList is sent as a JSON-encoded STRING, per
 * KDE's contract.
 */
object RunCommandPlugin : KdePlugin {
    override val incoming = setOf("kdeconnect.runcommand.request")
    override val outgoing = setOf("kdeconnect.runcommand")

    override fun onLinkReady(ctx: Context, link: KdeLink) = sendList(ctx, link)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        if (packet.getBoolean("requestCommandList")) { sendList(ctx, link); return true }
        val key = packet.getString("key")
        if (key.isNotEmpty()) execute(ctx, key)
        return true
    }

    private fun sendList(ctx: Context, link: KdeLink) {
        val map = JSONObject()
        for (cmd in KdeConnectConfig.get().commands) {
            map.put(cmd.key, JSONObject().put("name", cmd.name).put("command", cmd.name))
        }
        link.send(NetworkPacket.of("kdeconnect.runcommand") { put("commandList", map.toString()) })
    }

    private fun execute(ctx: Context, key: String) {
        val cmd = KdeConnectConfig.get().commands.firstOrNull { it.key == key } ?: return
        runCatching {
            val intent = when (cmd.type) {
                "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(cmd.value))
                else  -> ctx.packageManager.getLaunchIntentForPackage(cmd.value)
            } ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure { Log.i("KdeConnect/RunCmd", "exec $key failed: ${it.message}") }
    }
}

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

/**
 * kdeconnect.connectivity_report — report the phone's mobile network type +
 * signal level (0–4) to the desktop. Gated on READ_PHONE_STATE; without it we
 * report an UNKNOWN/0 entry so the desktop still shows the device.
 */
object ConnectivityReportPlugin : KdePlugin {
    override val incoming = setOf("kdeconnect.connectivity_report.request")
    override val outgoing = setOf("kdeconnect.connectivity_report")

    override fun onLinkReady(ctx: Context, link: KdeLink) { link.send(report(ctx)) }
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        link.send(report(ctx)); return true
    }

    private fun report(ctx: Context): NetworkPacket {
        var type = "Unknown"; var level = 0
        if (granted(ctx, Manifest.permission.READ_PHONE_STATE)) runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            type = networkType(tm.dataNetworkType)
            level = tm.signalStrength?.level ?: 0
        }
        val entry = JSONObject().put("networkType", type).put("signalStrength", level)
        return NetworkPacket.of("kdeconnect.connectivity_report") {
            put("signalStrengths", JSONObject().put("0", entry))
        }
    }

    private fun networkType(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0 -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
        else -> "Mobile"
    }
}

/**
 * kdeconnect.lock — report + control the phone lock state. Reading the state
 * (KeyguardManager) needs no permission; LOCKING needs the device-admin policy
 * ([KdeDeviceAdminReceiver]) which the user enrols once — until then a lock
 * request posts a prompt to enable it.
 */
object LockDevicePlugin : KdePlugin {
    private const val REQUEST = "kdeconnect.lock.request"
    private const val STATE   = "kdeconnect.lock"

    override val incoming = setOf(REQUEST, STATE)
    override val outgoing = setOf(STATE)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        when (packet.type) {
            REQUEST -> if (packet.getBoolean("requestLocked")) sendState(ctx, link)
            STATE   -> if (packet.has("setLocked")) {
                if (packet.getBoolean("setLocked")) lock(ctx)
                sendState(ctx, link)
            }
        }
        return true
    }

    private fun sendState(ctx: Context, link: KdeLink) {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        link.send(NetworkPacket.of(STATE) { put("isLocked", km.isDeviceLocked) })
    }

    private fun lock(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, KdeDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
        } else {
            KdeNotifications.post(ctx, "Remote lock",
                "Enable the 'KDE Connect lock' device-admin once to allow locking from the desktop.")
            runCatching {
                ctx.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Allows KDE Connect to lock this phone from your desktop.")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/**
 * kdeconnect.telephony — the desktop can mute the phone's ringer for an
 * incoming call (request_mute). Best-effort: silencing the ringer needs DND /
 * notification-policy access on newer Android; if denied it's a no-op.
 */
object TelephonyPlugin : KdePlugin {
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
