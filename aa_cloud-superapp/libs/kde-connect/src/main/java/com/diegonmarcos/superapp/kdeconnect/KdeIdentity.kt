package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * Our own device identity on the KDE-Connect mesh. The [deviceId] is a stable
 * per-install UUID (the certificate CN is bound to it, so it must never change
 * once a peer has paired with us). [deviceName] is the user-visible label.
 *
 * The identity packet is the FIRST thing written on a new link (in plaintext,
 * pre-TLS, per the protocol) and AGAIN over TLS for protocol v8.
 */
object KdeIdentity {
    const val PROTOCOL_VERSION = 8
    private const val PREFS = "kdeconnect_identity"
    private const val K_DEVICE_ID = "device_id"

    /** Stable, persisted device id — generated once per install. */
    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(K_DEVICE_ID, null)?.let { return it }
        // KDE Connect device ids are hex-ish tokens without dashes.
        val id = "cloud_" + UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(K_DEVICE_ID, id).apply()
        return id
    }

    /** User-visible name; build.json may override, else the phone model. */
    fun deviceName(ctx: Context): String =
        KdeConnectConfig.get().selfName.ifBlank {
            (android.os.Build.MODEL ?: "Cloud SuperApp").ifBlank { "Cloud SuperApp" }
        }

    /** The identity packet. [tcpPort] is the port OUR inbound link server
     *  listens on (advertised so a peer can connect back). [incoming] /
     *  [outgoing] are the plugin capability sets. [targetDeviceId] is set
     *  only when answering a specific peer (protocol ≥ tcp identity). */
    fun packet(
        ctx: Context,
        tcpPort: Int,
        incoming: Set<String>,
        outgoing: Set<String>,
        targetDeviceId: String? = null,
    ): NetworkPacket = NetworkPacket.of(NetworkPacket.TYPE_IDENTITY) {
        put("deviceId", deviceId(ctx))
        put("deviceName", deviceName(ctx))
        put("protocolVersion", PROTOCOL_VERSION)
        put("deviceType", "phone")
        put("incomingCapabilities", JSONArray(incoming.toList()))
        put("outgoingCapabilities", JSONArray(outgoing.toList()))
        put("tcpPort", tcpPort)
        if (targetDeviceId != null) put("targetDeviceId", targetDeviceId)
    }
}
