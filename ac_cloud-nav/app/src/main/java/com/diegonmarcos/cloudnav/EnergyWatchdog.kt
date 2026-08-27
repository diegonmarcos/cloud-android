package com.diegonmarcos.cloudnav

import android.app.usage.UsageStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.AudioManager
import android.net.TrafficStats
import android.os.PowerManager
import android.os.Process

/**
 * Device-level energy watchdog (Tier 1) — samples whole-device draw
 * alongside a vector of observable system states, so an attribution
 * pass can say WHICH states correlate with high drain on THIS device.
 *
 * The hard constraint: a normal app can't read per-other-app energy
 * ([android.os.BatteryStatsManager.getBatteryUsageStats] needs the
 * privileged BATTERY_STATS permission). What we CAN do without
 * privilege:
 *   • read whole-device instantaneous draw — BatterySessionStats
 *     already surfaces CURRENT_NOW (µA) + charge-counter deltas.
 *   • read the observable state vector each sample: screen on +
 *     brightness, the FOREGROUND app (UsageStats, already granted),
 *     CPU load, mobile/wifi state, audio, power-save / Doze, and our
 *     OWN uid's cpu jiffies + net bytes.
 *   • correlate draw against those states over many samples →
 *     "screen-on adds ~X mA", "weak-cellular ~Y mA", "app Z averages
 *     ~W mA while foreground". Empirical + device-specific — the
 *     foundation for a PRECISE save-energy mode that disables only
 *     what actually dominates, not a blanket list.
 *
 * Shizuku (Tier 2, separate module) layers ground-truth per-app mAh
 * on top by running `dumpsys batterystats` as shell uid; this Tier 1
 * model works with zero privilege and calibrates against it.
 */
object EnergyWatchdog {

    /** One sample row. Cumulative self-counters (selfCpuJiffies,
     *  selfRxBytes, selfTxBytes) are stored raw; attribution diffs
     *  adjacent rows to get per-interval cost. */
    data class Sample(
        val ts: Long,
        val drawMa: Int,        // whole-device; >0 discharge, <0 charge
        val powerW: Double,
        val battPct: Int,
        val battTempC: Double,
        val charging: Boolean,
        val screenOn: Boolean,
        val brightness: Int,    // 0..255, -1 unknown
        val fgPkg: String,      // foreground app pkg, "" unknown
        val cpuLoadPct: Int,    // 1-min loadavg as % of nCores, -1 unknown
        val mobileSignalDbm: Int, // -1 unknown
        val wifiRssi: Int,      // 0 / -127 unknown
        val audioActive: Boolean,
        val powerSave: Boolean,
        val deviceIdle: Boolean,
        val selfCpuJiffies: Long,
        val selfRxBytes: Long,
        val selfTxBytes: Long,
    )

    // ── Collection ──────────────────────────────────────────────────

    fun sample(ctx: Context, now: Long = System.currentTimeMillis()): Sample {
        val batt = runCatching { BatterySessionStats.read(ctx, now) }.getOrNull()
        val drawMa = batt?.currentUa?.let { it / 1000 } ?: 0
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        val brightness = runCatching {
            android.provider.Settings.System.getInt(
                ctx.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val powerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        val deviceIdle = runCatching { pm?.isDeviceIdleMode == true }.getOrDefault(false)

        val fgPkg = foregroundPackage(ctx, now)

        val cpuLoadPct = runCatching {
            val l = SysfsProc.cpuLoad()
            if (l != null && l.cores > 0) (l.loadAvg1m / l.cores * 100).toInt() else -1
        }.getOrDefault(-1)

        val mobileSignalDbm = runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager
            tm?.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm ?: -1
        }.getOrDefault(-1)

        val wifiRssi = runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.rssi ?: 0
        }.getOrDefault(0)

        val audioActive = runCatching {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
        }.getOrDefault(false)

        val myUid = Process.myUid()
        val selfRx = runCatching { TrafficStats.getUidRxBytes(myUid) }.getOrDefault(0L)
        val selfTx = runCatching { TrafficStats.getUidTxBytes(myUid) }.getOrDefault(0L)
        val selfCpu = runCatching { readSelfCpuJiffies() }.getOrDefault(0L)

        val s = Sample(
            ts = now,
            drawMa = drawMa,
            powerW = batt?.powerW ?: 0.0,
            battPct = batt?.curPct ?: -1,
            battTempC = batt?.batteryTempC ?: 0.0,
            charging = batt?.isCharging ?: false,
            screenOn = screenOn,
            brightness = brightness,
            fgPkg = fgPkg,
            cpuLoadPct = cpuLoadPct,
            mobileSignalDbm = mobileSignalDbm,
            wifiRssi = wifiRssi,
            audioActive = audioActive,
            powerSave = powerSave,
            deviceIdle = deviceIdle,
            selfCpuJiffies = selfCpu,
            selfRxBytes = selfRx,
            selfTxBytes = selfTx,
        )
        runCatching { EnergyStore(ctx).insert(s) }
        EnergyLedger.wake("bg.energy_sampler")
        return s
    }

    /** Foreground app via UsageStats events in the last 2 minutes —
     *  the most recent MOVE_TO_FOREGROUND wins. "" when usage access
     *  isn't granted or nothing is in the window. */
    private fun foregroundPackage(ctx: Context, now: Long): String = runCatching {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return ""
        val events = usm.queryEvents(now - 2 * 60_000L, now)
        val e = android.app.usage.UsageEvents.Event()
        var last = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = e.packageName ?: last
            }
        }
        last
    }.getOrDefault("")

    /** Sum utime+stime (fields 14,15) from /proc/self/stat — our own
     *  process CPU jiffies since start. */
    private fun readSelfCpuJiffies(): Long {
        val txt = java.io.File("/proc/self/stat").readText()
        // The comm field (2) may contain spaces/parens; split after ')'.
        val after = txt.substringAfterLast(')').trim().split(Regex("\\s+"))
        // After comm, fields are: state(0) ppid(1) ... utime is index 11,
        // stime index 12 in this 0-based post-comm slice.
        val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L
        val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L
        return utime + stime
    }

    // ── Attribution ─────────────────────────────────────────────────

    /** Group-by-state marginal-draw attribution over the stored
     *  samples. Returns a JSON-ready map. Interpretable + robust;
     *  full regression is overkill for v1. */
    fun attribution(ctx: Context): Map<String, Any> {
        val rows = runCatching { EnergyStore(ctx).all() }.getOrDefault(emptyList())
        // Only discharge samples carry meaningful draw attribution.
        val dis = rows.filter { !it.charging && it.drawMa > 0 }
        if (dis.isEmpty()) return mapOf("samples" to 0, "note" to "no discharge samples yet")

        fun avg(xs: List<Int>): Int = if (xs.isEmpty()) 0 else xs.sum() / xs.size
        val baseline = run {
            val idle = dis.filter { !it.screenOn }.map { it.drawMa }
            if (idle.isNotEmpty()) median(idle) else median(dis.map { it.drawMa })
        }

        // Marginal mA per discrete state = avg draw while state ON minus baseline.
        fun marginal(pred: (Sample) -> Boolean): Map<String, Int> {
            val on = dis.filter(pred).map { it.drawMa }
            return mapOf(
                "avg_ma" to avg(on),
                "marginal_ma" to (if (on.isEmpty()) 0 else avg(on) - baseline),
                "samples" to on.size,
            )
        }

        // Per foreground app: avg draw while it's foreground + screen-on,
        // ranked by an energy proxy (avg_ma × sample share).
        val byApp = dis.filter { it.screenOn && it.fgPkg.isNotEmpty() }
            .groupBy { it.fgPkg }
            .map { (pkg, ss) ->
                val a = avg(ss.map { it.drawMa })
                mapOf(
                    "pkg" to pkg,
                    "label" to runCatching {
                        ctx.packageManager.getApplicationLabel(
                            ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg),
                    "avg_ma" to a,
                    "samples" to ss.size,
                    "energy_proxy" to a * ss.size,
                )
            }
            .sortedByDescending { it["energy_proxy"] as Int }

        // Our own cost over the window: jiffies + bytes deltas (first→last).
        val self = run {
            val f = dis.first(); val l = dis.last()
            val jiffies = (l.selfCpuJiffies - f.selfCpuJiffies).coerceAtLeast(0)
            val rx = (l.selfRxBytes - f.selfRxBytes).coerceAtLeast(0)
            val tx = (l.selfTxBytes - f.selfTxBytes).coerceAtLeast(0)
            mapOf(
                "cpu_jiffies" to jiffies,
                "cpu_seconds" to "%.1f".format(jiffies / 100.0),  // CLK_TCK≈100
                "net_rx_bytes" to rx,
                "net_tx_bytes" to tx,
                "window_minutes" to ((l.ts - f.ts) / 60_000.0),
            )
        }

        return mapOf(
            "samples" to dis.size,
            "baseline_idle_ma" to baseline,
            "states" to mapOf(
                "screen_on" to marginal { it.screenOn },
                "audio_active" to marginal { it.audioActive },
                "power_save" to marginal { it.powerSave },
                "weak_cellular" to marginal { it.mobileSignalDbm in -120..-100 },
                "high_cpu" to marginal { it.cpuLoadPct >= 60 },
                "bright_screen" to marginal { it.screenOn && it.brightness >= 160 },
            ),
            "by_foreground_app" to byApp,
            "self" to self,
        )
    }

    /** True iff Usage Access (PACKAGE_USAGE_STATS appop) is allowed —
     *  the actual gate for per-app foreground time. checkSelfPermission
     *  always returns DENIED for appop perms, so we must ask AppOps. */
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
            ao.unsafeCheckOpNoThrow("android:get_usage_stats", Process.myUid(), ctx.packageName)
        else
            @Suppress("DEPRECATION")
            ao.checkOpNoThrow("android:get_usage_stats", Process.myUid(), ctx.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    data class AppEstimate(val pkg: String, val label: String, val fgMs: Long, val mAh: Double)

    /**
     * AccuBattery-style per-app energy ESTIMATE — no privilege, no
     * Shizuku. Exactly how AccuBattery builds its list:
     *   per-app mAh ≈ app foreground-time × avg screen-on discharge mA
     *
     * Foreground time per app comes from [UsageStatsManager] over the
     * current discharge session (since last unplug, else last 24h).
     * Avg screen-on discharge mA comes from our own watchdog samples
     * (screen-on, discharging); falls back to the live CURRENT_NOW
     * reading when we have no samples yet. Ranked by mAh, top first.
     */
    fun perAppEstimate(ctx: Context, now: Long = System.currentTimeMillis()): List<AppEstimate> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return emptyList()
        val bs = runCatching { BatterySessionStats.read(ctx, now) }.getOrNull()
        val start = (bs?.unplugTs?.takeIf { it > 0 }) ?: (now - 24 * 60 * 60_000L)

        // Per-app foreground ms over the session (merge duplicate rows).
        val fg = HashMap<String, Long>()
        runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
        }.getOrNull()?.forEach { u ->
            if (u.totalTimeInForeground > 0L)
                fg[u.packageName] = (fg[u.packageName] ?: 0L) + u.totalTimeInForeground
        }
        if (fg.isEmpty()) return emptyList()

        // Avg screen-on discharge mA from our samples; else live draw.
        val rows = runCatching { EnergyStore(ctx).all() }.getOrDefault(emptyList())
            .filter { !it.charging && it.screenOn && it.drawMa > 0 }
        val avgMa = if (rows.isNotEmpty()) rows.map { it.drawMa }.average()
        else (bs?.currentUa?.let { kotlin.math.abs(it) / 1000.0 } ?: 0.0)

        val pm = ctx.packageManager
        return fg.entries
            .filter { it.value >= 1000L }            // ignore sub-second blips
            .map { (pkg, ms) ->
                AppEstimate(
                    pkg = pkg,
                    label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg),
                    fgMs = ms,
                    mAh = avgMa * ms / 3_600_000.0,
                )
            }
            .sortedByDescending { it.mAh }
    }

    private fun median(xs: List<Int>): Int {
        if (xs.isEmpty()) return 0
        val s = xs.sorted()
        return s[s.size / 2]
    }
}

/** Bounded SQLite ring for energy samples. Prunes to [CAP] newest rows
 *  on each insert so the table never grows unbounded. */
class EnergyStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $T (ts INTEGER PRIMARY KEY, draw_ma INTEGER, power_w REAL, " +
                "batt_pct INTEGER, batt_temp REAL, charging INTEGER, screen_on INTEGER, " +
                "brightness INTEGER, fg_pkg TEXT, cpu_load INTEGER, mobile_dbm INTEGER, " +
                "wifi_rssi INTEGER, audio INTEGER, power_save INTEGER, device_idle INTEGER, " +
                "self_cpu INTEGER, self_rx INTEGER, self_tx INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T"); onCreate(db)
    }

    fun insert(s: EnergyWatchdog.Sample) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO $T VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                s.ts, s.drawMa, s.powerW, s.battPct, s.battTempC,
                if (s.charging) 1 else 0, if (s.screenOn) 1 else 0, s.brightness,
                s.fgPkg, s.cpuLoadPct, s.mobileSignalDbm, s.wifiRssi,
                if (s.audioActive) 1 else 0, if (s.powerSave) 1 else 0,
                if (s.deviceIdle) 1 else 0, s.selfCpuJiffies, s.selfRxBytes, s.selfTxBytes,
            ))
        db.execSQL("DELETE FROM $T WHERE ts NOT IN (SELECT ts FROM $T ORDER BY ts DESC LIMIT $CAP)")
    }

    fun all(): List<EnergyWatchdog.Sample> {
        val out = ArrayList<EnergyWatchdog.Sample>()
        readableDatabase.rawQuery("SELECT * FROM $T ORDER BY ts ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(EnergyWatchdog.Sample(
                    ts = c.getLong(0), drawMa = c.getInt(1), powerW = c.getDouble(2),
                    battPct = c.getInt(3), battTempC = c.getDouble(4), charging = c.getInt(5) == 1,
                    screenOn = c.getInt(6) == 1, brightness = c.getInt(7), fgPkg = c.getString(8) ?: "",
                    cpuLoadPct = c.getInt(9), mobileSignalDbm = c.getInt(10), wifiRssi = c.getInt(11),
                    audioActive = c.getInt(12) == 1, powerSave = c.getInt(13) == 1,
                    deviceIdle = c.getInt(14) == 1, selfCpuJiffies = c.getLong(15),
                    selfRxBytes = c.getLong(16), selfTxBytes = c.getLong(17),
                ))
            }
        }
        return out
    }

    fun recent(limit: Int): List<EnergyWatchdog.Sample> = all().takeLast(limit)

    fun clear() { writableDatabase.execSQL("DELETE FROM $T") }

    companion object {
        private const val DB = "energy_watchdog.db"
        private const val T = "energy_sample"
        private const val CAP = 8000  // ~5.5 days at 1/min, bounded
    }
}
