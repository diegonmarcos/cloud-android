package com.diegonmarcos.superapp.onehand

import android.content.Context

/**
 * Per-user override of each swipe's action, persisted on-device. build.json
 * seeds the DEFAULT action for every slot; the user re-maps any slot here and
 * the override wins. Key = "<handleId>.<slotKey>" → OneHandAction.name.
 * Absent key = fall back to the baked default.
 */
object OneHandPrefs {
    private const val FILE = "onehand_prefs"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Override string wins; else the baked default; null = unmapped slot. */
    fun actionFor(ctx: Context, handleId: String, slotKey: String, default: GestureAction?): GestureAction? {
        val raw = prefs(ctx).getString("$handleId.$slotKey", null) ?: return default
        return GestureAction.parse(raw) ?: default
    }

    fun setAction(ctx: Context, handleId: String, slotKey: String, action: GestureAction?) {
        val e = prefs(ctx).edit()
        if (action == null) e.putString("$handleId.$slotKey", OneHandAction.NONE.name)
        else e.putString("$handleId.$slotKey", action.serialize())
        e.apply()
    }

    fun clear(ctx: Context, handleId: String, slotKey: String) {
        prefs(ctx).edit().remove("$handleId.$slotKey").apply()
    }

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
    }
}
