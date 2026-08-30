package com.diegonmarcos.superapp.news

import android.content.Context
import org.json.JSONArray

/**
 * Local bookmarks — articles the user explicitly saved. Stored as a
 * full [GdeltArticle] snapshot (not just title/domain/seendate/tone)
 * so a saved item still renders with every field the UI expects even
 * after it ages out of NewsStore's per-topic feed cache. Same house
 * style as NewsStore/CalendarStore: one prefs file, JSON array of the
 * model's own toJson()/fromJson.
 */
object SavedStore {
    private const val PREFS = "news_saved"
    private const val KEY = "saved_articles"

    // User-driven, one tap at a time — nowhere near CalendarStore's
    // feed-size concerns, but still bounded defensively so an
    // accidental save-everything loop can't grow this prefs value
    // without limit.
    private const val MAX_SAVED = 2000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Most-recently-saved first. */
    fun saved(ctx: Context): List<GdeltArticle> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { GdeltArticle.fromJson(o) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    fun isSaved(ctx: Context, url: String): Boolean =
        url.isNotBlank() && saved(ctx).any { it.url == url }

    /** Adds [article] if not already saved (by url), else removes it.
     *  Returns the new saved state: true if it is now saved, false if
     *  the toggle just removed it. */
    fun toggle(ctx: Context, article: GdeltArticle): Boolean {
        val current = saved(ctx).toMutableList()
        val idx = current.indexOfFirst { it.url == article.url }
        val nowSaved: Boolean
        if (idx >= 0) {
            current.removeAt(idx)
            nowSaved = false
        } else {
            current.add(0, article)
            nowSaved = true
        }
        val capped = if (current.size > MAX_SAVED) current.take(MAX_SAVED) else current
        val arr = JSONArray()
        for (a in capped) arr.put(a.toJson())
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        return nowSaved
    }
}
