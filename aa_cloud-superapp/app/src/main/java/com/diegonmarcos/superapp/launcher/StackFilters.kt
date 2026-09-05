package com.diegonmarcos.superapp.launcher

import android.content.Context

/**
 * Persisted selections for a page's `filters_<page>` toggle row, plus the
 * unread watermark the Show toggle reads.
 *
 * commit() rather than apply(): this app IS the launcher, so Android
 * restarts it many times an hour. A selection still sitting in apply()'s
 * async write queue when the process dies is a selection that silently
 * reverts — the same way an in-memory dismissal made the home banner look
 * permanent.
 *
 * There is no per-entry read flag here, and that is the point. Neither
 * [com.diegonmarcos.superapp.core.NotificationStore] nor
 * [com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore]
 * records whether a single entry was read or dismissed, and adding such a
 * field would mean adding a filter over a value nothing ever writes. Both
 * stores DO already carry `ts`, so "unread" is derived instead: newer than
 * the previous visit. No schema change, and nothing can drift out of sync
 * with a flag that was never set.
 */
object StackFilters {

    private const val PREF = "stack_filters"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** The chosen option id for [filter] on [page], or its declared default. */
    fun selected(ctx: Context, page: String, filter: Sections.StackFilter): String {
        val stored = prefs(ctx).getString("$page/${filter.id}", null) ?: return filter.default
        // A stored id that build.json no longer declares would strand the row
        // on an option the user can no longer see, and therefore cannot undo.
        return if (filter.options.any { it.id == stored }) stored else filter.default
    }

    fun select(ctx: Context, page: String, filterId: String, optionId: String) {
        prefs(ctx).edit().putString("$page/$filterId", optionId).commit()
    }

    /** When [page] was last opened. Everything with a newer `ts` is unread. */
    fun lastSeen(ctx: Context, page: String): Long =
        prefs(ctx).getLong("$page/seen_at", 0L)

    fun markSeen(ctx: Context, page: String, at: Long) {
        prefs(ctx).edit().putLong("$page/seen_at", at).commit()
    }
}
