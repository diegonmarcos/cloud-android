package com.diegonmarcos.superapp.datamanager

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process

/**
 * data-manager (network side) — per-app total bytes (rx+tx) ranked desc.
 * Reads [NetworkStatsManager], authorised by the same PACKAGE_USAGE_STATS
 * special-access grant AppUsageProvider uses. WiFi + mobile summed over a
 * 7-day window; subscriberId is null (we only need a RANKING, not exact
 * per-SIM mobile attribution — and null avoids the READ_PHONE_STATE
 * requirement). uid → package resolved via PackageManager; only real
 * application uids (>= FIRST_APPLICATION_UID) are considered so shared
 * system/network uids don't dominate.
 *
 * Wrapped in `runCatching` per transport → empty list on any failure (no
 * grant, OEM throw) so the dependent smart folder just hides.
 */
object AppNetworkProvider {

    private const val DAY_MS = 24L * 60 * 60_000

    /** Packages ranked by total bytes (rx+tx) desc over the last 7 days. */
    fun topByBytes(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyList()
        val start = now - 7 * DAY_MS
        val bytesByUid = HashMap<Int, Long>()

        for (transport in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            runCatching {
                val summary = nsm.querySummary(transport, null, start, now)
                val bucket = NetworkStats.Bucket()
                while (summary.hasNextBucket()) {
                    summary.getNextBucket(bucket)
                    bytesByUid[bucket.uid] =
                        (bytesByUid[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
                }
                summary.close()
            }
        }
        if (bytesByUid.isEmpty()) return emptyList()

        val pm = ctx.packageManager
        val out = ArrayList<String>()
        bytesByUid.entries
            .filter { it.key >= Process.FIRST_APPLICATION_UID && it.value > 0L }
            .sortedByDescending { it.value }
            .forEach { e ->
                val pkg = runCatching { pm.getPackagesForUid(e.key) }.getOrNull()?.firstOrNull()
                    ?: return@forEach
                if (pkg !in out) out.add(pkg)
            }
        return out
    }
}
