package com.diegonmarcos.superapp.datamanager

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Permanent per-MONTH data-usage ledger.
 *
 * [DataUsageProvider] answers everything live from NetworkStatsManager,
 * which is the right call for recent windows — but the OS only retains a
 * rolling window (~90 days on most devices, less on some OEMs). Once a
 * month falls out of that window it is gone forever. This store is the
 * long-term memory: monthly rows are tiny, so we keep them essentially
 * forever while the OS keeps the fine-grained truth for the recent past.
 *
 * Three newline-separated ledgers in SharedPreferences, same shape as
 * [com.diegonmarcos.superapp.battery.BatteryHistoryStore]:
 *
 *   "months" — exact device totals, one line per month, oldest first:
 *       yyyy-MM|mobileRx|mobileTx|wifiRx|wifiTx
 *     capped at [MAX_MONTHS] (10 years).
 *
 *   "apps" — top-[TOP_APPS] apps of each month (the long tail is dropped;
 *     the month TOTAL above stays exact regardless):
 *       yyyy-MM|pkg|bytes
 *     kept only for the most recent [MAX_APP_MONTHS] months.
 *
 *   "subs" — per-subscription mobile totals, labelled by carrier + slot AT
 *     RECORD TIME so the history stays readable across SIM swaps (a slot
 *     index alone would silently re-point at a different carrier):
 *       yyyy-MM|slot|carrier|rx|tx
 *     capped at [MAX_MONTHS].
 *
 * Worst case ≈ 20 KB. No Room, nothing to migrate.
 *
 * ROLL-FORWARD: [refresh] is idempotent and cheap, and is piggybacked on
 * the existing 15-minute BatterySessionWorker tick — no new scheduler.
 *
 * BACKFILL SAFETY: a month is only (re)written when the OS actually
 * returns bytes for it, or when we have nothing stored for it yet. That
 * is what stops a month ageing out of the OS window from overwriting a
 * good stored row with zeroes.
 */
object DataUsageHistoryStore {

    const val MAX_MONTHS = 120
    const val MAX_APP_MONTHS = 24
    const val TOP_APPS = 15

    /** How far back to look on first run, in months. The OS will only
     *  answer for the part it still retains; the rest comes back empty
     *  and is simply not recorded. */
    const val BACKFILL_MONTHS = 6

    private const val PREFS = "data_usage_history"
    private const val KEY_MONTHS = "months"
    private const val KEY_APPS = "apps"
    private const val KEY_SUBS = "subs"

    /** One stored month of device-wide usage. */
    data class MonthTotals(val month: String, val totals: DataUsageProvider.Totals)

    /** One stored app row inside a month. */
    data class MonthApp(val month: String, val pkg: String, val bytes: Long)

    /** One stored subscription row inside a month. */
    data class MonthSub(
        val month: String,
        val slot: Int,
        val carrier: String,
        val rx: Long,
        val tx: Long,
    ) {
        val total: Long get() = rx + tx
        val label: String get() = if (carrier.isBlank()) "SIM ${slot + 1}" else carrier
    }

    // ── read ──────────────────────────────────────────────────────────
    /** Stored months, NEWEST FIRST. */
    fun months(ctx: Context): List<MonthTotals> =
        lines(ctx, KEY_MONTHS).mapNotNull { parseMonth(it) }.asReversed()

    /** Stored top-app rows for [month], largest first. */
    fun appsOf(ctx: Context, month: String): List<MonthApp> =
        lines(ctx, KEY_APPS).mapNotNull { parseApp(it) }
            .filter { it.month == month }
            .sortedByDescending { it.bytes }

    /** Stored per-subscription rows for [month], largest first. */
    fun subsOf(ctx: Context, month: String): List<MonthSub> =
        lines(ctx, KEY_SUBS).mapNotNull { parseSub(it) }
            .filter { it.month == month }
            .sortedByDescending { it.total }

    /** True once at least one month has been folded in. */
    fun isEmpty(ctx: Context): Boolean = lines(ctx, KEY_MONTHS).isEmpty()

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_MONTHS).remove(KEY_APPS).remove(KEY_SUBS).apply()
    }

    // ── write ─────────────────────────────────────────────────────────
    /**
     * Fold every month the OS can still answer for into the ledger,
     * newest [BACKFILL_MONTHS] months inclusive. Idempotent: re-running
     * refreshes the current month (which is still accumulating) and
     * leaves settled months untouched unless the OS still has data for
     * them, in which case the row is simply rewritten with the same
     * numbers.
     *
     * Safe to call from any thread that isn't the main one — it issues a
     * handful of NetworkStatsManager queries. Silently no-ops without
     * usage access.
     */
    fun refresh(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!DataUsageProvider.hasUsageAccess(ctx)) return

        val storedMonths = lines(ctx, KEY_MONTHS).mapNotNull { parseMonth(it) }
            .associateBy { it.month }.toMutableMap()
        val storedApps = lines(ctx, KEY_APPS).mapNotNull { parseApp(it) }.toMutableList()
        val storedSubs = lines(ctx, KEY_SUBS).mapNotNull { parseSub(it) }.toMutableList()

        for (back in 0 until BACKFILL_MONTHS) {
            val (start, end) = monthBounds(now, back)
            if (start >= now) continue
            val key = monthKey(start)
            val totals = runCatching { DataUsageProvider.deviceTotals(ctx, start, minOf(end, now)) }
                .getOrDefault(DataUsageProvider.Totals())

            // Nothing from the OS AND something already stored → the month
            // has aged out of the OS window. Keep what we have.
            if (totals.total <= 0L && storedMonths.containsKey(key)) continue
            if (totals.total <= 0L) continue

            storedMonths[key] = MonthTotals(key, totals)

            // Top apps for the month — replace this month's rows wholesale.
            val apps = runCatching { DataUsageProvider.perApp(ctx, start, minOf(end, now)) }
                .getOrDefault(emptyList())
            if (apps.isNotEmpty()) {
                storedApps.removeAll { it.month == key }
                apps.take(TOP_APPS).forEach {
                    storedApps.add(MonthApp(key, it.pkg, it.totals.total))
                }
            }

            // Per-subscription mobile split — labelled by carrier + slot at
            // record time so SIM swaps stay readable in history.
            val subs = runCatching { DataUsageProvider.mobilePerSubscription(ctx, start, minOf(end, now)) }
                .getOrDefault(emptyList())
            if (subs.isNotEmpty()) {
                storedSubs.removeAll { it.month == key }
                subs.forEach {
                    storedSubs.add(MonthSub(key, it.slot, it.carrier, it.totals.mobileRx, it.totals.mobileTx))
                }
            }
        }

        val monthsOut = storedMonths.values.sortedBy { it.month }.takeLast(MAX_MONTHS)
        val keepAppMonths = monthsOut.map { it.month }.takeLast(MAX_APP_MONTHS).toSet()

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MONTHS, monthsOut.joinToString("\n") {
                "${it.month}|${it.totals.mobileRx}|${it.totals.mobileTx}|${it.totals.wifiRx}|${it.totals.wifiTx}"
            })
            .putString(KEY_APPS, storedApps.filter { it.month in keepAppMonths }
                .sortedWith(compareBy({ it.month }, { -it.bytes }))
                .joinToString("\n") { "${it.month}|${it.pkg}|${it.bytes}" })
            .putString(KEY_SUBS, storedSubs.filter { m -> monthsOut.any { it.month == m.month } }
                .sortedBy { it.month }
                .joinToString("\n") { "${it.month}|${it.slot}|${it.carrier}|${it.rx}|${it.tx}" })
            .apply()
    }

    // ── month helpers ─────────────────────────────────────────────────
    /** [start, end) millis of the month [back] months before [now]. */
    fun monthBounds(now: Long, back: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -back)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start to cal.timeInMillis
    }

    /** "yyyy-MM" key for a millis instant. */
    fun monthKey(ts: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(ts))

    /** "March 2026" for display. */
    fun monthLabel(key: String): String = runCatching {
        val d = SimpleDateFormat("yyyy-MM", Locale.US).parse(key) ?: return key
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(d)
    }.getOrDefault(key)

    /** [start, end) millis for a stored "yyyy-MM" key. */
    fun boundsOf(key: String): Pair<Long, Long>? = runCatching {
        val d = SimpleDateFormat("yyyy-MM", Locale.US).parse(key) ?: return null
        val cal = Calendar.getInstance().apply { time = d }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        start to cal.timeInMillis
    }.getOrNull()

    // ── parsing ───────────────────────────────────────────────────────
    private fun lines(ctx: Context, key: String): List<String> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "") ?: "")
            .split('\n').filter { it.isNotBlank() }

    private fun parseMonth(line: String): MonthTotals? {
        val f = line.split('|')
        if (f.size != 5) return null
        return MonthTotals(f[0], DataUsageProvider.Totals(
            mobileRx = f[1].toLongOrNull() ?: return null,
            mobileTx = f[2].toLongOrNull() ?: return null,
            wifiRx   = f[3].toLongOrNull() ?: return null,
            wifiTx   = f[4].toLongOrNull() ?: return null,
        ))
    }

    private fun parseApp(line: String): MonthApp? {
        val f = line.split('|')
        if (f.size != 3) return null
        return MonthApp(f[0], f[1], f[2].toLongOrNull() ?: return null)
    }

    private fun parseSub(line: String): MonthSub? {
        val f = line.split('|')
        if (f.size != 5) return null
        return MonthSub(
            month   = f[0],
            slot    = f[1].toIntOrNull() ?: return null,
            carrier = f[2],
            rx      = f[3].toLongOrNull() ?: return null,
            tx      = f[4].toLongOrNull() ?: return null,
        )
    }
}
