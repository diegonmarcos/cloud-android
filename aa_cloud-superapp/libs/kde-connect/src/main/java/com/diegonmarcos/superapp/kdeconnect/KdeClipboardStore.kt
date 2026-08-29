package com.diegonmarcos.superapp.kdeconnect

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray

/**
 * Both clipboards, as HISTORY rather than a single slot.
 *
 * ## Why this is a local history and not a fetch
 * KDE Connect's clipboard plugin carries exactly one string per packet
 * (`kdeconnect.clipboard`, and `kdeconnect.clipboard.connect` which adds a
 * timestamp). There is NO packet in the protocol that asks a peer for its
 * clipboard history — the desktop's Klipper history is not exposed over KDE
 * Connect at all, so nothing on this side can pull the entries the desktop had
 * before we started listening.
 *
 * What is obtainable is every clip the desktop pushes from now on, kept here
 * instead of overwritten. That makes "the desktop's clipboard" a real list that
 * grows as you use it, which is the same end state a fetch would reach — just
 * forwards rather than backwards.
 *
 * Ours is thinner still: Android hands an app the CURRENT primary clip and
 * only while it is focused, so our list is what we managed to observe while
 * this page was open. [KdeClipboardStore] does not pretend otherwise; the card
 * says so.
 */
object KdeClipboardStore {
    private const val PREFS = "kdeconnect_clipboard"
    private const val K_HOST = "host_history"
    private const val K_OURS = "our_history"
    private const val K_AT = "host_at"

    /** Kept small on purpose: this is a convenience list, not an archive, and
     *  it is read in full every time the card renders. */
    const val CAP = 50

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context, key: String): MutableList<String> = runCatching {
        val arr = JSONArray(sp(ctx).getString(key, "[]"))
        MutableList(arr.length()) { arr.getString(it) }
    }.getOrDefault(mutableListOf())

    private fun save(ctx: Context, key: String, list: List<String>) {
        sp(ctx).edit().putString(key, JSONArray(list.take(CAP)).toString()).apply()
    }

    /** Newest first, de-duplicated: re-copying something moves it up rather
     *  than filling the list with the same string. */
    private fun push(ctx: Context, key: String, text: String) {
        if (text.isEmpty()) return
        val list = load(ctx, key)
        list.remove(text)
        list.add(0, text)
        save(ctx, key, list)
    }

    fun rememberHost(ctx: Context, text: String) {
        push(ctx, K_HOST, text)
        sp(ctx).edit().putLong(K_AT, System.currentTimeMillis()).apply()
    }

    /** Record whatever is on this phone right now, if anything new. Called
     *  when the card renders — the only moment we are reliably allowed to
     *  read the clipboard at all. */
    fun rememberOurs(ctx: Context) = push(ctx, K_OURS, ourText(ctx))

    fun hostHistory(ctx: Context): List<String> = load(ctx, K_HOST)
    fun ourHistory(ctx: Context): List<String> = load(ctx, K_OURS)
    fun hostAt(ctx: Context): Long = sp(ctx).getLong(K_AT, 0L)

    /** This phone's clipboard. Empty when Android refuses the read, which it
     *  does unless we are the focused app. */
    fun ourText(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    }.getOrDefault("")

    /** Both lists as one, newest-first, each string once. Cheap because both
     *  are already newest-first: interleaving would need timestamps per entry,
     *  which the protocol does not give us for the host side. */
    fun merged(ctx: Context): List<String> =
        (hostHistory(ctx) + ourHistory(ctx)).distinct().take(CAP)

    /** Fold the merged list into BOTH sides, so each has everything. Returns
     *  how many entries the combined list holds. */
    fun mergeAll(ctx: Context): Int {
        val all = merged(ctx)
        save(ctx, K_HOST, all)
        save(ctx, K_OURS, all)
        return all.size
    }

}
