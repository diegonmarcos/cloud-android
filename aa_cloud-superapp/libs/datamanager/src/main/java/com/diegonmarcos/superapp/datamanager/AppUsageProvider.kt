package com.diegonmarcos.superapp.datamanager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * data-manager (usage side) — per-app recency / active-window / launch-count
 * metrics from [UsageStatsManager]. Zero privilege beyond
 * PACKAGE_USAGE_STATS, which the user grants once via
 * Configs → Permissions → Usage access (same grant the Battery & Usage
 * screen uses). Every query is wrapped in `runCatching` and degrades to an
 * empty list on any failure — a missing grant or a throwing OEM
 * implementation just hides the dependent smart folder, never crashes.
 *
 * All methods return a RANKED list of package names, best-first, with NO
 * cap — the caller filters to its own launchable-app set and takes its own
 * limit (so a system app that outranks everything can't starve the folder).
 *
 * Mirrors the proven UsageStatsManager query shape in
 * libs:battery/EnergyWatchdog.perAppEstimate.
 */
object AppUsageProvider {

    private const val DAY_MS = 24L * 60 * 60_000
    private const val HOUR_MS = 60L * 60_000

    private fun usm(ctx: Context): UsageStatsManager? =
        ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Packages ranked by most-recent use (lastTimeUsed desc), 30-day window. */
    fun recentUsed(ctx: Context, now: Long = System.currentTimeMillis()): List<String> =
        rankByLastUse(ctx, now - 30 * DAY_MS, now, floor = 0L)

    /** Packages used within the last [windowH] hours, most-recent first. */
    fun activeSince(ctx: Context, windowH: Int, now: Long = System.currentTimeMillis()): List<String> {
        val start = now - windowH.coerceAtLeast(1) * HOUR_MS
        return rankByLastUse(ctx, start, now, floor = start)
    }

    /** Packages ranked by launch count — MOVE_TO_FOREGROUND events over 7 days. */
    fun mostOpened(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val u = usm(ctx) ?: return emptyList()
        val start = now - 7 * DAY_MS
        val counts = HashMap<String, Int>()
        runCatching {
            val ev = u.queryEvents(start, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    counts[e.packageName] = (counts[e.packageName] ?: 0) + 1
                }
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Shared: max lastTimeUsed per package over [start,end], keeping only
     *  rows whose lastTimeUsed is at/after [floor], ranked most-recent first. */
    private fun rankByLastUse(ctx: Context, start: Long, end: Long, floor: Long): List<String> {
        val u = usm(ctx) ?: return emptyList()
        val last = HashMap<String, Long>()
        runCatching {
            u.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
        }.getOrNull()?.forEach { s ->
            if (s.lastTimeUsed >= floor && s.lastTimeUsed > 0L) {
                last[s.packageName] = maxOf(last[s.packageName] ?: 0L, s.lastTimeUsed)
            }
        }
        return last.entries.sortedByDescending { it.value }.map { it.key }
    }
}
