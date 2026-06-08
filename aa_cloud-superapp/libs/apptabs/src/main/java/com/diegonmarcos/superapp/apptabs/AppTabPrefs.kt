package com.diegonmarcos.superapp.apptabs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last-N opened SuperApp entries — a mix of:
 *   • SuperApp sections   (section:mail, section:wallet, …)
 *   • SuperApp pages      (page:health/summary, page:wallet/cards, …)
 *   • External Android apps launched from the Phone tab.
 *
 * Strict LRU at cap=6 — the 7th record evicts the oldest. Producers
 * call [record] on every successful navigation; the GRID UI in
 * [AppTabsFragment] reads [all] each time it renders.
 *
 * SharedPreferences file name "app_tabs" so it can't collide with
 * the in-app browser's "tabs" file (the rename Tabs→Browser shipped
 * earlier exactly to free this namespace).
 */
class AppTabPrefs(context: Context) {

    /** A single recorded entry. Sealed so the UI can render each
     *  shape correctly (section icon vs page label vs external-app
     *  icon) without a string-typed switch. */
    sealed class Entry {
        abstract val ts: Long
        abstract val key: String   // for de-dup + LRU recency

        data class SectionEntry(
            val sectionId: String,
            val label: String,
            val iconName: String,
            override val ts: Long,
        ) : Entry() {
            override val key get() = "section:$sectionId"
        }

        data class PageEntry(
            val sectionId: String,
            val pageId: String,
            val label: String,
            val iconName: String,
            override val ts: Long,
        ) : Entry() {
            override val key get() = "page:$sectionId/$pageId"
        }

        data class ExternalAppEntry(
            val packageName: String,
            val label: String,
            override val ts: Long,
        ) : Entry() {
            override val key get() = "app:$packageName"
        }
    }

    private val sp = context.applicationContext
        .getSharedPreferences("app_tabs", Context.MODE_PRIVATE)

    /** Most-recent first. Up to [CAP] entries. */
    fun all(): List<Entry> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val parsed = parse(o) ?: continue
            out.add(parsed)
        }
        return out.sortedByDescending { it.ts }
    }

    fun recordSection(sectionId: String, label: String, iconName: String) {
        push(Entry.SectionEntry(sectionId, label, iconName, System.currentTimeMillis()))
    }

    fun recordPage(sectionId: String, pageId: String, label: String, iconName: String) {
        push(Entry.PageEntry(sectionId, pageId, label, iconName, System.currentTimeMillis()))
    }

    fun recordExternalApp(packageName: String, label: String) {
        push(Entry.ExternalAppEntry(packageName, label, System.currentTimeMillis()))
    }

    fun clear() = save(emptyList())

    private fun push(e: Entry) {
        val key = e.key
        // Move-to-top: drop any existing entry with the same key,
        // prepend the fresh copy, cap to LRU window.
        val updated = ArrayList<Entry>().apply {
            add(e)
            addAll(all().filter { it.key != key })
        }
        save(updated.take(CAP))
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (entry in entries) arr.put(serialise(entry))
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun serialise(e: Entry): JSONObject = JSONObject().apply {
        put("ts", e.ts)
        when (e) {
            is Entry.SectionEntry -> {
                put("type", "section")
                put("sectionId", e.sectionId)
                put("label", e.label)
                put("icon", e.iconName)
            }
            is Entry.PageEntry -> {
                put("type", "page")
                put("sectionId", e.sectionId)
                put("pageId", e.pageId)
                put("label", e.label)
                put("icon", e.iconName)
            }
            is Entry.ExternalAppEntry -> {
                put("type", "app")
                put("packageName", e.packageName)
                put("label", e.label)
            }
        }
    }

    private fun parse(o: JSONObject): Entry? {
        val ts = o.optLong("ts", 0L)
        return when (o.optString("type")) {
            "section" -> Entry.SectionEntry(
                sectionId = o.optString("sectionId"),
                label     = o.optString("label"),
                iconName  = o.optString("icon"),
                ts        = ts,
            )
            "page" -> Entry.PageEntry(
                sectionId = o.optString("sectionId"),
                pageId    = o.optString("pageId"),
                label     = o.optString("label"),
                iconName  = o.optString("icon"),
                ts        = ts,
            )
            "app" -> Entry.ExternalAppEntry(
                packageName = o.optString("packageName"),
                label       = o.optString("label"),
                ts          = ts,
            )
            else -> null
        }
    }

    companion object {
        private const val KEY = "entries"
        const val CAP = 6
    }
}
