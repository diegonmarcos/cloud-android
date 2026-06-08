package com.diegonmarcos.superapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted ring buffer of phone notifications captured by
 * [PhoneNotificationListenerService]. Last 50 entries, deduped by
 * StatusBarNotification.key so a music-player progress update doesn't
 * spam 30 rows for the same notification — the newest update replaces
 * the prior entry in place.
 *
 * Persistence: single JSONArray string in SharedPreferences. Compact,
 * fast to read on panel render, survives process death. Cleared on
 * user demand (clear()) — not auto-cleared on dismissal: the whole
 * point of a launcher-side notification history is to see what flew
 * by even after the system dismissed it.
 */
object PhoneNotificationStore {
    private const val PREF = "phone_notif_store"
    private const val KEY = "entries"
    private const val MAX = 50

    data class Entry(
        val key: String,
        val ts: Long,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
    )

    fun all(ctx: Context): List<Entry> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Entry(
                        key         = o.optString("key"),
                        ts          = o.optLong("ts"),
                        packageName = o.optString("pkg"),
                        appLabel    = o.optString("app"),
                        title       = o.optString("title"),
                        text        = o.optString("text"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Upsert by key, newest first, capped at MAX. */
    fun push(ctx: Context, e: Entry) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val rest = all(ctx).filter { it.key != e.key }
        val next = (listOf(e) + rest).take(MAX)
        val arr = JSONArray()
        for (x in next) {
            arr.put(JSONObject()
                .put("key", x.key)
                .put("ts", x.ts)
                .put("pkg", x.packageName)
                .put("app", x.appLabel)
                .put("title", x.title)
                .put("text", x.text))
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
