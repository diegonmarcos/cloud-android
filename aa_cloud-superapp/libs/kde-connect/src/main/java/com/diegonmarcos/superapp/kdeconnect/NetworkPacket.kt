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
) {
    fun getString(key: String, def: String = ""): String = body.optString(key, def)
    fun getBoolean(key: String, def: Boolean = false): Boolean = body.optBoolean(key, def)
    fun getInt(key: String, def: Int = 0): Int = body.optInt(key, def)
    fun has(key: String): Boolean = body.has(key)

    /** Serialize to the wire form INCLUDING the trailing newline. */
    fun serialize(): String =
        JSONObject().apply {
            put("id", id)
            put("type", type)
            put("body", body)
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

        /** Build a packet of [type] with a freshly-stamped id. The caller
         *  fills [build] against the body. `id` is wall-clock ms — but
         *  Date/System.currentTimeMillis is fine here (runtime packet, never
         *  part of a reproducible build artefact). */
        fun of(type: String, build: JSONObject.() -> Unit = {}): NetworkPacket =
            NetworkPacket(type, JSONObject().apply(build), System.currentTimeMillis())

        /** Parse one wire line. Returns null on malformed input rather than
         *  throwing — a bad line must never kill the read loop. */
        fun parse(line: String): NetworkPacket? = runCatching {
            val o = JSONObject(line)
            NetworkPacket(
                type = o.getString("type"),
                body = o.optJSONObject("body") ?: JSONObject(),
                id   = o.optLong("id", System.currentTimeMillis()),
            )
        }.getOrNull()
    }
}
