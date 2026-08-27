package com.diegonmarcos.superapp.contacts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the RawContacts that came from a [SocialImport] (LinkedIn CSV,
 * Instagram JSON, …) so they survive process death and re-launch without
 * re-picking the export file. Deliberately NOT device contacts: those live
 * in ContactsContract already and re-reading them via [DeviceContacts] is
 * cheap and always fresh, so duplicating them here would just be a second,
 * staler copy to keep in sync. One JSON file keyed by source id, replaced
 * wholesale per import — an import is "the current state of that source",
 * not a diff to merge into what was there before.
 */
class SocialStore(context: Context) {

    private val file = File(context.filesDir, "social-contacts.json")

    @Synchronized
    fun all(): List<RawContact> {
        val root = readRoot()
        val out = ArrayList<RawContact>()
        root.keys().forEach { source ->
            val arr = root.optJSONArray(source) ?: return@forEach
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(fromJson(source, it)) }
            }
        }
        return out
    }

    @Synchronized
    fun replaceSource(source: String, contacts: List<RawContact>) {
        val root = readRoot()
        val arr = JSONArray()
        contacts.forEach { arr.put(toJson(it)) }
        root.put(source, arr)
        writeRoot(root)
    }

    @Synchronized
    fun removeSource(source: String) {
        val root = readRoot()
        if (root.has(source)) {
            root.remove(source)
            writeRoot(root)
        }
    }

    @Synchronized
    fun counts(): Map<String, Int> {
        val root = readRoot()
        val out = LinkedHashMap<String, Int>()
        root.keys().forEach { source -> out[source] = (root.optJSONArray(source)?.length() ?: 0) }
        return out
    }

    private fun readRoot(): JSONObject =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()

    private fun writeRoot(root: JSONObject) {
        file.writeText(root.toString())
    }

    private fun toJson(c: RawContact): JSONObject = JSONObject().apply {
        put("name", c.name)
        put("phones", JSONArray(c.phones))
        put("emails", JSONArray(c.emails))
        put("org", c.org)
        put("title", c.title)
        put("urls", JSONArray(c.urls))
        put("handles", JSONObject(c.handles as Map<*, *>))
    }

    private fun fromJson(source: String, o: JSONObject): RawContact {
        fun strings(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        }
        val handles = LinkedHashMap<String, String>()
        o.optJSONObject("handles")?.let { h -> h.keys().forEach { k -> handles[k] = h.optString(k) } }
        return RawContact(
            source = source,
            name = o.optString("name"),
            phones = strings("phones"),
            emails = strings("emails"),
            org = o.optString("org"),
            title = o.optString("title"),
            urls = strings("urls"),
            handles = handles,
        )
    }
}
