package com.diegonmarcos.superapp.firewall

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings

/**
 * Reads the live conditions the rules are keyed on — all no-root:
 *  - [transport]  from ConnectivityManager
 *  - screen state from PowerManager.isInteractive
 *  - app foreground from UsageStatsManager (needs usage-access; when the
 *    grant is missing we degrade to screen-state only)
 */
object FirewallConditions {

    fun transport(ctx: Context): Transport {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Transport.OTHER
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return Transport.OTHER
        // VPN wins: a cloud-VPN tunnel is the effective transport when present.
        // ponytail: our own firewall tun also reads as VPN — Phase-3 firestack
        // distinguishes the WG peer from the local tun; interim this is best-effort.
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
    }

    private fun screenOn(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** True when usage-access has been granted — the reliable AppOps check
     *  (an event query is a false-negative on an idle device). */
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val aom = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else
            @Suppress("DEPRECATION")
            aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Most-recently-foregrounded package in the last [windowMs], or null. */
    private fun foregroundPkg(ctx: Context, windowMs: Long = 10_000): String? = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val ev = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (events.getNextEvent(ev)) {
            if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
            ) last = ev.packageName
        }
        last
    }.getOrNull()

    /**
     * Is [pkg] currently in the background? True unless the screen is on AND
     * [pkg] is the foreground app. Without usage-access we can't know the
     * foreground app, so we degrade to device screen state (screen-off ⇒
     * background, screen-on ⇒ treated as foreground).
     */
    fun isBackground(ctx: Context, pkg: String): Boolean {
        if (!screenOn(ctx)) return true
        if (!hasUsageAccess(ctx)) return false
        return foregroundPkg(ctx) != pkg
    }

    /** Intent action to send the user to the usage-access grant screen. */
    const val USAGE_ACCESS_SETTINGS = Settings.ACTION_USAGE_ACCESS_SETTINGS
}
