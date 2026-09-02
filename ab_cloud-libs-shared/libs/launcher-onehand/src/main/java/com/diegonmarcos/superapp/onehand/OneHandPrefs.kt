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

    /**
     * One-time repair of overrides nobody chose.
     *
     * The Configs editor attached its spinner listener straight after
     * setSelection, and setSelection POSTS its callback — so simply opening
     * that screen persisted an override for every slot at whatever was on
     * display. Unmapped slots showed "None", so they were written as NONE, and
     * from then on the stored NONE beat any default later shipped in
     * build.json. A device that had visited the screen once could never receive
     * a new default again.
     *
     * The editor no longer does that. This clears what it already wrote: every
     * stored NONE is dropped once, so those slots fall back to the baked
     * default. A deliberate "None" is lost in the process, which is the honest
     * trade — the bug means we cannot tell a chosen None from a written one,
     * and unmapped-by-accident was overwhelmingly the common case. Any real
     * choice is one spinner tap to restore, and is then respected.
     *
     * Guarded by a marker so it happens exactly once per install.
     */
    fun pruneSpuriousNones(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(PRUNED_KEY, false)) return
        val e = p.edit()
        for ((k, v) in p.all) {
            // "<handleId>.<slotKey>" entries only — never `enabled` / `trigger`.
            if (k.contains('.') && v == OneHandAction.NONE.name) e.remove(k)
        }
        e.putBoolean(PRUNED_KEY, true).apply()
    }

    private const val PRUNED_KEY = "spurious_nones_pruned_v1"

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
    }

    fun trigger(ctx: Context, default: OneHandConfig.Trigger): OneHandConfig.Trigger {
        val raw = prefs(ctx).getString("trigger", null) ?: return default
        return runCatching { OneHandConfig.Trigger.valueOf(raw) }.getOrDefault(default)
    }

    fun setTrigger(ctx: Context, t: OneHandConfig.Trigger) {
        prefs(ctx).edit().putString("trigger", t.name).apply()
    }

    /** Debug: force the (normally invisible) handles to a bright visible bar so
     *  their placement/size can be verified on-device without a rebuild. */
    fun debugVisible(ctx: Context): Boolean = prefs(ctx).getBoolean("debug_visible", false)

    fun setDebugVisible(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("debug_visible", on).apply()
    }
}
