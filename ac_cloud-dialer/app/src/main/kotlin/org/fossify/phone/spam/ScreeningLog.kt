package org.fossify.phone.spam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Cloud Dialer screened-call history (patch 0013). Pure JSON model — no Android
 * deps — so it unit-tests on the JVM like SpamMatcher. The Android side
 * (helpers/ScreeningLogStore.kt) persists the serialized form to a private file
 * so silently rejected calls (contacts_only mode, spam rules, blocked numbers)
 * are visible to the user instead of vanishing without a trace.
 */
object ScreeningLog {

    data class Entry(val number: String, val timestamp: Long, val reason: String)

    // ponytail: flat capped list in one JSON file — a Room table if the history
    // ever needs querying beyond "show newest N".
    const val MAX_ENTRIES = 200

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse a serialized log. Malformed input or entries → skipped (fail open, log is best-effort). */
    fun parse(json: String): List<Entry> {
        val out = ArrayList<Entry>()
        val arr = runCatching { lenient.parseToJsonElement(json).jsonObject["entries"]?.jsonArray }
            .getOrNull() ?: return out
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val ts = o["ts"]?.jsonPrimitive?.longOrNull ?: continue
            val number = o["number"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
            out.add(Entry(number, ts, reason))
        }
        return out
    }

    fun serialize(entries: List<Entry>): String = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("entries", buildJsonArray {
            entries.forEach { e ->
                add(buildJsonObject {
                    put("number", JsonPrimitive(e.number))
                    put("ts", JsonPrimitive(e.timestamp))
                    put("reason", JsonPrimitive(e.reason))
                })
            }
        })
    }.toString()

    /** Prepend a new entry (newest first), dropping the tail beyond [MAX_ENTRIES]. */
    fun add(entries: List<Entry>, entry: Entry): List<Entry> =
        (listOf(entry) + entries).take(MAX_ENTRIES)
}
