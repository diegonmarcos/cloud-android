package com.diegonmarcos.superapp.tabs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's open in-app browser tabs (URL + last-known
 * title) so they survive across launches. Stored as a JSON array
 * in SharedPreferences — small, plain, no extra dep.
 *
 * Schema: [{"url":"https://…","title":"…","ts":1234567890}]
 */
class TabPrefs(context: Context) {

    data class Tab(val url: String, val title: String, val ts: Long, val previewPath: String = "")

    private val sp = context.applicationContext
        .getSharedPreferences("tabs", Context.MODE_PRIVATE)

    /** Most-recently-added FIRST. */
    fun all(): List<Tab> {
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Tab>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Tab(
                url         = o.optString("url"),
                title       = o.optString("title", o.optString("url")),
                ts          = o.optLong("ts", 0L),
                previewPath = o.optString("preview", ""),
            ))
        }
        return out.sortedByDescending { it.ts }
    }

    /** Add (or move-to-top if URL already present) a tab. */
    fun add(url: String, title: String = url): Tab {
        if (url.isBlank()) return Tab("", "", 0L)
        val now = System.currentTimeMillis()
        val existing = all().toMutableList()
        existing.removeAll { it.url == url }
        existing.add(0, Tab(url, title, now))
        save(existing)
        return Tab(url, title, now)
    }

    /** Update a tab's title in place (called when WebView's onReceivedTitle fires). */
    fun updateTitle(url: String, title: String) {
        val list = all().map { if (it.url == url) it.copy(title = title) else it }
        save(list)
    }

    /** Update a tab's preview screenshot path (PNG in cache dir). */
    fun updatePreview(url: String, path: String) {
        val list = all().map { if (it.url == url) it.copy(previewPath = path) else it }
        save(list)
    }

    fun remove(url: String) {
        save(all().filterNot { it.url == url })
    }

    fun clear() = save(emptyList())

    fun activeUrl(): String? = sp.getString(KEY_ACTIVE, null)
    fun setActive(url: String?) {
        sp.edit().putString(KEY_ACTIVE, url).apply()
    }

    private fun save(tabs: List<Tab>) {
        val arr = JSONArray()
        for (t in tabs) {
            arr.put(JSONObject().apply {
                put("url",     t.url)
                put("title",   t.title)
                put("ts",      t.ts)
                put("preview", t.previewPath)
            })
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY        = "tabs_json"
        private const val KEY_ACTIVE = "active_url"
    }
}
