package com.diegonmarcos.superapp.kdeconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The last clipboard the desktop pushed to us, kept so the Configs › KDE page
 * can SHOW it.
 *
 * It has to be stored rather than read back out of the system clipboard,
 * because on Android 10+ the write in [ClipboardPlugin] is best-effort: an app
 * that is not foreground (or not the default IME / focused app) is refused
 * silently. Without this the page could only ever display "whatever is on this
 * phone", which is exactly the half of the pair the user cannot see.
 *
 * There is no toggle of its own here — [KdePluginPrefs] already gates the
 * `clipboard` plugin for both dispatch and capability advertisement, so that
 * one switch IS "clipboard sync on/off" and a second would only be able to
 * disagree with it.
 */
object KdeClipboardStore {
    private const val PREFS = "kdeconnect_clipboard"
    private const val K_TEXT = "host_text"
    private const val K_AT = "host_at"

    /** Remember what the peer sent (called from [ClipboardPlugin]). */
    fun rememberHost(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_TEXT, text).putLong(K_AT, System.currentTimeMillis()).apply()
    }

    fun hostText(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_TEXT, "").orEmpty()

    /** Epoch millis of the last push, 0 when nothing has arrived. */
    fun hostAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_AT, 0L)

    /** This phone's clipboard. Empty when Android refuses the read — which it
     *  does unless we are the focused app, so this is only meaningful while the
     *  page is actually on screen. */
    fun ourText(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    }.getOrDefault("")

    /** Put the stored host clipboard onto this phone — the manual half of the
     *  sync, for when the automatic write was refused. */
    fun applyHostLocally(ctx: Context): Boolean = runCatching {
        val text = hostText(ctx)
        if (text.isEmpty()) return false
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("KDE Connect", text)) != null
    }.getOrDefault(false)
}
