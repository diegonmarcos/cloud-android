package com.diegonmarcos.superapp.contacts

import com.diegonmarcos.superapp.core.DataBackendService
import org.json.JSONObject

/**
 * [ContactsEngine] behind core's IDataBackend, shipped in
 * Cloud-Lib-Contacts.apk.
 *
 * READ_CONTACTS is the caller's grant, not ours - a second package holding its
 * own contacts permission would be a worse story, not a better one - so every
 * method that can touch device contacts takes the caller's grant state as an
 * argument rather than checking its own.
 */
class ContactsBackendService : DataBackendService() {

    private val engine by lazy { ContactsEngine(applicationContext) }

    override fun methodNames(): Array<String> =
        arrayOf("state", "people", "person", "removeSource", "invalidate", "seed", "hasData")

    override fun dispatch(method: String, args: Array<String>): String {
        // arg 0 is uniformly the caller's READ_CONTACTS grant.
        val readDevice = args.getOrNull(0)?.toBoolean() ?: false
        return when (method) {
            "state" -> JSONObject()
                .put("sources", engine.sources(readDevice))
                .put("channels", engine.channels())
                .put("peopleCount", engine.count(readDevice))
                .toString()
            "people"       -> engine.peopleRows(readDevice).toString()
            "person"       -> engine.person(args.getOrNull(1).orEmpty(), readDevice).toString()
            "removeSource" -> engine.removeSource(args.getOrNull(1).orEmpty()).toString()
            "invalidate"   -> { engine.invalidate(); """{"ok":true}""" }
            // One-time handoff so the cutover cannot orphan imports - see
            // ContactsEngine.seed.
            "seed"         -> engine.seed(args.getOrNull(1).orEmpty()).toString()
            "hasData"      -> JSONObject().put("hasData", engine.hasData()).toString()
            else -> throw IllegalArgumentException("unknown method: $method")
        }
    }
}
