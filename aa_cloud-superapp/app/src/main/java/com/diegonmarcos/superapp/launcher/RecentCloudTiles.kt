package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * Small LRU of recently-opened Suite→Cloud tile targets, backed by
 * SharedPreferences.
 *
 * Cloud tiles have no existing pin/favorite/most-used signal the way
 * Phone apps do (UsageStatsManager, via AppUsageProvider) — this is the
 * one real per-tile signal available, so it backs the sole "Smart
 * Folders" subsection (Recently Used) on the merged Suite→Cloud page
 * ([GroupedTilesFragment]).
 *
 * [recordOpen] is called from the single dispatch chokepoint —
 * MainActivity.onTileClicked — so every surface that fires a tile click
 * contributes, not just the Suite page itself.
 */
object RecentCloudTiles {
    private const val PREFS = "recent_cloud_tiles"
    private const val KEY = "targets"
    private const val MAX = 12
    private const val SEP = ""

    /** Record [target] as just-opened — moves it to the front of the
     *  LRU, dropping anything past [MAX]. No-op for a blank target. */
    fun recordOpen(ctx: Context, target: String) {
        if (target.isBlank()) return
        val sp = prefs(ctx)
        val next = (listOf(target) + read(sp).filterNot { it == target }).take(MAX)
        sp.edit().putString(KEY, next.joinToString(SEP)).apply()
    }

    /** Ordered most-recent-first list of previously-opened tile targets. */
    fun recent(ctx: Context): List<String> = read(prefs(ctx))

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(sp: SharedPreferences): List<String> =
        sp.getString(KEY, null)?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()
}
