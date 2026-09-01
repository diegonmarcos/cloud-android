package com.diegonmarcos.superapp.battery

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded history of COMPLETED battery sessions + per-day aggregates.
 *
 * [BatterySessionStats] only ever knows about the CURRENT session — it
 * anchors on the last plug/unplug and drops the opposite anchor the
 * moment the direction flips. That flip is exactly the moment a session
 * ENDS, so this store is written from those two branches in
 * [BatterySessionStats.read]: the session that is about to be discarded
 * gets appended here first.
 *
 * Storage is SharedPreferences (no Room — nothing here justifies a
 * database) with two newline-separated, hard-capped text ledgers:
 *
 *   "sessions" — one line per completed session, newest LAST:
 *       <D|C>|startTs|endTs|startPct|endPct
 *     capped at [MAX_SESSIONS]; oldest lines are dropped.
 *
 *   "days" — one line per calendar day, newest LAST:
 *       yyyy-MM-dd|drainedPct|chargedPct|dischargeMs|chargeMs|sessions
 *     capped at [MAX_DAYS]. A session is attributed wholly to the day it
 *     ENDED on — sessions routinely straddle midnight and splitting them
 *     would need per-minute sampling we deliberately don't do.
 *
 * Worst case footprint: 200 sessions × ~40 B + 90 days × ~45 B ≈ 12 KB.
 */
object BatteryHistoryStore {

    const val MAX_SESSIONS = 200
    const val MAX_DAYS = 90

    private const val PREFS = "battery_history"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_DAYS = "days"

    /** One completed charge or discharge run. */
    data class Session(
        val charging: Boolean,
        val startTs: Long,
        val endTs: Long,
        val startPct: Int,
        val endPct: Int,
    ) {
        val durationMs: Long get() = (endTs - startTs).coerceAtLeast(0L)
        /** Signed percent delta — negative while discharging. */
        val deltaPct: Int get() = endPct - startPct
        /** Absolute percent per hour; 0 when the run is too short to be meaningful. */
        val ratePerHour: Double
            get() {
                val hours = durationMs / 3_600_000.0
                if (hours < 0.0084) return 0.0 // under 30 s — noise
                return kotlin.math.abs(deltaPct) / hours
            }
    }

    /** Aggregate for one calendar day. */
    data class Day(
        val date: String,        // yyyy-MM-dd
        val drainedPct: Int,
        val chargedPct: Int,
        val dischargeMs: Long,
        val chargeMs: Long,
        val sessions: Int,
    )

    /**
     * Append one completed session and fold it into its day aggregate.
     * Silently ignores runs that carry no information (missing anchor,
     * unknown percentage, zero elapsed) so the ledger stays clean.
     */
    fun record(
        ctx: Context,
        charging: Boolean,
        startTs: Long,
        startPct: Int,
        endTs: Long,
        endPct: Int,
    ) {
        if (startTs <= 0L || endTs <= startTs) return
        if (startPct !in 0..100 || endPct !in 0..100) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val line = "${if (charging) "C" else "D"}|$startTs|$endTs|$startPct|$endPct"
        val kept = (sp.getString(KEY_SESSIONS, "") ?: "")
            .split('\n').filter { it.isNotBlank() }
            .plus(line)
            .takeLast(MAX_SESSIONS)

        val session = Session(charging, startTs, endTs, startPct, endPct)
        sp.edit()
            .putString(KEY_SESSIONS, kept.joinToString("\n"))
            .putString(KEY_DAYS, foldIntoDays(sp.getString(KEY_DAYS, "") ?: "", session))
            .apply()
    }

    /** Completed sessions, NEWEST FIRST. */
    fun sessions(ctx: Context): List<Session> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSIONS, "") ?: "")
            .split('\n')
            .mapNotNull { parseSession(it) }
            .asReversed()

    /** Day aggregates, NEWEST FIRST. */
    fun days(ctx: Context): List<Day> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DAYS, "") ?: "")
            .split('\n')
            .mapNotNull { parseDay(it) }
            .asReversed()

    /** Wipe the ledger — surfaced from the history dialog. */
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSIONS).remove(KEY_DAYS).apply()
    }

    /** Mean %/h across every stored session of the requested direction;
     *  0.0 when nothing usable is stored yet. */
    fun averageRatePerHour(list: List<Session>, charging: Boolean): Double {
        val rates = list.filter { it.charging == charging && it.ratePerHour > 0.0 }
            .map { it.ratePerHour }
        return if (rates.isEmpty()) 0.0 else rates.sum() / rates.size
    }

    private fun foldIntoDays(raw: String, s: Session): String {
        val date = dayKey(s.endTs)
        val existing = raw.split('\n').mapNotNull { parseDay(it) }
        val prior = existing.firstOrNull { it.date == date }
        val merged = Day(
            date        = date,
            drainedPct  = (prior?.drainedPct ?: 0) + if (s.charging) 0 else -s.deltaPct.coerceAtMost(0),
            chargedPct  = (prior?.chargedPct ?: 0) + if (s.charging) s.deltaPct.coerceAtLeast(0) else 0,
            dischargeMs = (prior?.dischargeMs ?: 0L) + if (s.charging) 0L else s.durationMs,
            chargeMs    = (prior?.chargeMs ?: 0L) + if (s.charging) s.durationMs else 0L,
            sessions    = (prior?.sessions ?: 0) + 1,
        )
        return existing.filter { it.date != date }
            .plus(merged)
            .sortedBy { it.date }
            .takeLast(MAX_DAYS)
            .joinToString("\n") {
                "${it.date}|${it.drainedPct}|${it.chargedPct}|${it.dischargeMs}|${it.chargeMs}|${it.sessions}"
            }
    }

    private fun parseSession(line: String): Session? {
        val f = line.split('|')
        if (f.size != 5) return null
        return Session(
            charging = f[0] == "C",
            startTs  = f[1].toLongOrNull() ?: return null,
            endTs    = f[2].toLongOrNull() ?: return null,
            startPct = f[3].toIntOrNull() ?: return null,
            endPct   = f[4].toIntOrNull() ?: return null,
        )
    }

    private fun parseDay(line: String): Day? {
        val f = line.split('|')
        if (f.size != 6) return null
        return Day(
            date        = f[0],
            drainedPct  = f[1].toIntOrNull() ?: return null,
            chargedPct  = f[2].toIntOrNull() ?: return null,
            dischargeMs = f[3].toLongOrNull() ?: return null,
            chargeMs    = f[4].toLongOrNull() ?: return null,
            sessions    = f[5].toIntOrNull() ?: return null,
        )
    }

    private fun dayKey(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
}
