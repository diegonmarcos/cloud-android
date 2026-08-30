package com.diegonmarcos.superapp.datamanager

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.util.Calendar

/**
 * data-manager (data-usage side) — the engine behind Configs/About →
 * Network → "Data Usage". Pure data, no UI, no R references.
 *
 * Everything is answered LIVE from [NetworkStatsManager], which keeps its
 * own multi-month history in system storage. We deliberately persist
 * NOTHING: any local ledger would only duplicate (and eventually
 * contradict) the OS one. That is the whole reason this file has no
 * SharedPreferences / Room, unlike its sibling BatteryHistoryStore, where
 * the OS keeps nothing.
 *
 * Authorised by the same PACKAGE_USAGE_STATS special-access grant
 * [AppUsageProvider] and [AppNetworkProvider] already rely on, and that
 * the app already asks for in Configs/About → Permissions ("Set Usage
 * Access"). No new permission and no new request flow.
 *
 * subscriberId is null throughout: passing a real one requires
 * READ_PHONE_STATE and only buys per-SIM attribution we don't surface.
 *
 * Every query is wrapped in `runCatching` — a missing grant or an OEM
 * that throws degrades to zeroes / an empty list, never a crash.
 */
object DataUsageProvider {

    /** Bytes split by transport, for one time window. */
    data class Totals(
        val mobileRx: Long = 0L,
        val mobileTx: Long = 0L,
        val wifiRx: Long = 0L,
        val wifiTx: Long = 0L,
    ) {
        val mobile: Long get() = mobileRx + mobileTx
        val wifi: Long get() = wifiRx + wifiTx
        val total: Long get() = mobile + wifi

        operator fun plus(o: Totals) = Totals(
            mobileRx + o.mobileRx, mobileTx + o.mobileTx,
            wifiRx + o.wifiRx, wifiTx + o.wifiTx,
        )
    }

    /** One app's usage over the queried window. */
    data class AppUsage(
        val uid: Int,
        val pkg: String,
        val label: String,
        val totals: Totals,
    )

    /** One calendar day of usage, for the per-app drill-down. */
    data class DayUsage(val startMs: Long, val totals: Totals)

    private val TRANSPORTS = intArrayOf(
        ConnectivityManager.TYPE_MOBILE,
        ConnectivityManager.TYPE_WIFI,
    )

    // ── permission ────────────────────────────────────────────────────
    /**
     * True when the usage-access special grant is held. Same AppOps check
     * the Permissions screen renders its "Set Usage Access" state from —
     * duplicated here (5 lines) rather than depending on :libs:battery so
     * datamanager keeps its single :libs:core dependency.
     */
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ao.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ao.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** The settings screen the user must visit to grant it. Callers start
     *  this intent action directly — the same one PermissionsFragment's
     *  "Set Usage Access" button uses. */
    const val USAGE_ACCESS_SETTINGS = android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS

    // ── periods ───────────────────────────────────────────────────────
    /** Midnight (local) at the start of today. */
    fun startOfToday(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Midnight (local) on the 1st of the current month. */
    fun startOfMonth(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = startOfToday(now)
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

    /** Start of the day [daysAgo] days back. */
    fun startOfDaysAgo(daysAgo: Int, now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = startOfToday(now)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }.timeInMillis

    // ── device-wide ───────────────────────────────────────────────────
    /**
     * Whole-device totals for the window, via querySummaryForDevice.
     * This is the honest device number — it includes traffic the per-app
     * query can't attribute (tethering, uid 0 kernel traffic), so it will
     * usually be slightly LARGER than the sum of [perApp].
     */
    fun deviceTotals(ctx: Context, start: Long, end: Long): Totals {
        val nsm = statsManager(ctx) ?: return Totals()
        var out = Totals()
        for (transport in TRANSPORTS) {
            runCatching {
                val b = nsm.querySummaryForDevice(transport, null, start, end)
                out += transportTotals(transport, b.rxBytes, b.txBytes)
            }
        }
        return out
    }

    // ── per app ───────────────────────────────────────────────────────
    /**
     * Per-app usage over the window, ranked by total bytes descending.
     * uids are folded to a single representative package (shared-uid apps
     * report as one bucket at the kernel level — there is no way to split
     * them, so we don't pretend to).
     */
    fun perApp(ctx: Context, start: Long, end: Long): List<AppUsage> {
        val nsm = statsManager(ctx) ?: return emptyList()
        val byUid = HashMap<Int, Totals>()

        for (transport in TRANSPORTS) {
            runCatching {
                val summary = nsm.querySummary(transport, null, start, end)
                val bucket = NetworkStats.Bucket()
                while (summary.hasNextBucket()) {
                    summary.getNextBucket(bucket)
                    byUid[bucket.uid] = (byUid[bucket.uid] ?: Totals()) +
                        transportTotals(transport, bucket.rxBytes, bucket.txBytes)
                }
                summary.close()
            }
        }

        val pm = ctx.packageManager
        return byUid.entries
            .filter { it.key >= Process.FIRST_APPLICATION_UID && it.value.total > 0L }
            .mapNotNull { (uid, totals) ->
                val pkg = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.firstOrNull()
                    ?: return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                AppUsage(uid, pkg, label, totals)
            }
            .sortedByDescending { it.totals.total }
    }

    /**
     * Day-by-day usage for ONE uid across the window — the per-app
     * drill-down. One [NetworkStatsManager] summary query per day per
     * transport; at a 31-day month that is 62 cheap system calls, so this
     * still belongs off the main thread but needs no caching layer.
     */
    fun dailyForUid(ctx: Context, uid: Int, start: Long, end: Long): List<DayUsage> {
        val nsm = statsManager(ctx) ?: return emptyList()
        val out = ArrayList<DayUsage>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis < end) {
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = minOf(cal.timeInMillis, end)
            var totals = Totals()
            for (transport in TRANSPORTS) {
                runCatching {
                    val summary = nsm.querySummary(transport, null, dayStart, dayEnd)
                    val bucket = NetworkStats.Bucket()
                    while (summary.hasNextBucket()) {
                        summary.getNextBucket(bucket)
                        if (bucket.uid == uid) {
                            totals += transportTotals(transport, bucket.rxBytes, bucket.txBytes)
                        }
                    }
                    summary.close()
                }
            }
            out.add(DayUsage(dayStart, totals))
        }
        return out
    }

    // ── helpers ───────────────────────────────────────────────────────
    private fun statsManager(ctx: Context): NetworkStatsManager? =
        ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

    private fun transportTotals(transport: Int, rx: Long, tx: Long): Totals =
        if (transport == ConnectivityManager.TYPE_WIFI) Totals(wifiRx = rx, wifiTx = tx)
        else Totals(mobileRx = rx, mobileTx = tx)

    /** Shared byte formatter so every surface renders identical strings. */
    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }
}
