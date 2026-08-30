package com.diegonmarcos.superapp.kdeconnect

import org.json.JSONObject

/**
 * One KDE-Connect wire packet. Original implementation of the documented
 * interop format (clean-room — not KDE's GPL source):
 *
 *   {"id": <long ms>, "type": "kdeconnect.<plugin>", "body": { ... }}\n
 *
 * Packets are newline-delimited UTF-8 JSON on the (TLS) stream. `type`
 * selects the plugin/handler; `body` is the plugin payload. A handful of
 * well-known types are surfaced as constants so the dispatcher and the
 * plugins share one vocabulary.
 */
class NetworkPacket private constructor(
    val type: String,
    val body: JSONObject,
    val id: Long,
    /** KDE payload transfer: total bytes streamed on a side channel (0 = none). */
    val payloadSize: Long = 0L,
    /** {"port": <int>} the payload is served on; null when no payload. */
    val payloadTransferInfo: JSONObject? = null,
) {
    fun getString(key: String, def: String = ""): String = body.optString(key, def)
    fun getBoolean(key: String, def: Boolean = false): Boolean = body.optBoolean(key, def)
    fun getInt(key: String, def: Int = 0): Int = body.optInt(key, def)
    fun getLong(key: String, def: Long = 0L): Long = body.optLong(key, def)
    fun has(key: String): Boolean = body.has(key)

    /** Port the payload is served on, or null when there's no payload. */
    fun payloadPort(): Int? = payloadTransferInfo?.optInt("port")?.takeIf { it > 0 }

    /** Serialize to the wire form INCLUDING the trailing newline. Emits the
     *  top-level payloadSize + payloadTransferInfo siblings (KDE format) only
     *  when this packet carries a payload. */
    fun serialize(): String =
        JSONObject().apply {
            put("id", id)
            put("type", type)
            put("body", body)
            if (payloadSize > 0L && payloadTransferInfo != null) {
                put("payloadSize", payloadSize)
                put("payloadTransferInfo", payloadTransferInfo)
            }
        }.toString() + "\n"

    companion object {
        const val TYPE_IDENTITY      = "kdeconnect.identity"
        const val TYPE_PAIR          = "kdeconnect.pair"
        const val TYPE_PING          = "kdeconnect.ping"
        const val TYPE_CLIPBOARD     = "kdeconnect.clipboard"
        const val TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
        const val TYPE_FINDMYPHONE   = "kdeconnect.findmyphone.request"
        const val TYPE_NOTIFICATION  = "kdeconnect.notification"
        const val TYPE_BATTERY       = "kdeconnect.battery"
        const val TYPE_BATTERY_REQUEST = "kdeconnect.battery.request"
        const val TYPE_SHARE         = "kdeconnect.share.request"
        const val TYPE_MPRIS         = "kdeconnect.mpris"
        const val TYPE_MPRIS_REQUEST = "kdeconnect.mpris.request"
        const val TYPE_SYSTEMVOLUME  = "kdeconnect.systemvolume"
        const val TYPE_SYSTEMVOLUME_REQUEST = "kdeconnect.systemvolume.request"

        /** Build a packet of [type] with a freshly-stamped id. The caller
         *  fills [build] against the body. `id` is wall-clock ms — but
         *  Date/System.currentTimeMillis is fine here (runtime packet, never
         *  part of a reproducible build artefact). */
        fun of(type: String, build: JSONObject.() -> Unit = {}): NetworkPacket =
            NetworkPacket(type, JSONObject().apply(build), System.currentTimeMillis())

        /** Build a packet that carries a payload (KDE file transfer): the
         *  sender serves [payloadSize] bytes on [port] (TLS); the receiver
         *  connects to the other end's address on that port to pull them. */
        fun withPayload(type: String, payloadSize: Long, port: Int, build: JSONObject.() -> Unit = {}): NetworkPacket =
            NetworkPacket(
                type, JSONObject().apply(build), System.currentTimeMillis(),
                payloadSize, JSONObject().put("port", port),
            )

        /** Parse one wire line. Returns null on malformed input rather than
         *  throwing — a bad line must never kill the read loop. */
        fun parse(line: String): NetworkPacket? = runCatching {
            val o = JSONObject(line)
            NetworkPacket(
                type = o.getString("type"),
                body = o.optJSONObject("body") ?: JSONObject(),
                id   = o.optLong("id", System.currentTimeMillis()),
                payloadSize = o.optLong("payloadSize", 0L),
                payloadTransferInfo = o.optJSONObject("payloadTransferInfo"),
            )
        }.getOrNull()
    }
}
