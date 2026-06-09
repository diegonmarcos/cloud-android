package com.diegonmarcos.superapp

import java.io.File

/**
 * Reader for `/sys/class/...` and `/proc/...` — every file we
 * touch here is world-readable on every modern Android kernel.
 * No DUMP, no QUERY_ALL_PACKAGES, no signature perm, no root.
 * Same path AccuBattery, Termux's `top`, and every battery / CPU
 * monitoring app on the Play Store uses to surface kernel-side
 * telemetry the Android framework hides behind privileged APIs.
 *
 * IMPORTANT (engine gotcha): never write `/sys/class/&#42;` (or
 * any other literal `/&#42;`) inside a KDoc on this file — Kotlin
 * block comments are nested-aware, so a literal `/&#42;` in the
 * docstring opens a NESTED comment that swallows the rest of the
 * file. Use `...` or `<node>` as the placeholder instead.
 *
 * Each public function returns a list of (label, formatted-value)
 * pairs so the renderer (Configs/About#SYSFS-PROC) can iterate and
 * paint rows uniformly. Callers that just want one number (e.g.
 * SystemInfoPopup → CPU load) consume the structured snapshots
 * directly via [cpuLoad] etc.
 *
 * Unit conventions (matches kernel power_supply spec):
 *   • voltage in µV   → divide by 1e6 for V
 *   • current in µA   → divide by 1e6 for A (Samsung kernels lie and
 *                         report mA; same |x| < 100_000 heuristic as
 *                         BatteryChargerSpec auto-rescales)
 *   • charge  in µAh  → divide by 1000 for mAh
 *   • energy  in µWh  → divide by 1e6 for Wh
 *   • power   in µW   → divide by 1e6 for W
 *   • temp    in deci-°C → divide by 10 for °C
 *   • jiffies (proc)  → divide by sysconf(_SC_CLK_TCK), usually 100Hz
 *                         on Android → seconds = jiffies / 100
 *
 * Any path that can't be read returns null silently — render-side
 * skips the row instead of showing "—". Some sysfs nodes only
 * appear when a specific input is connected (e.g. `wireless/online`
 * is gone entirely on devices without a Qi coil), and that's
 * normal — the absence is itself information.
 */
object SysfsProc {

    // ─────────────────────── primitive readers ───────────────────────

    private fun readText(path: String): String? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()
    }.getOrNull()

    private fun readLong(path: String): Long? = readText(path)?.toLongOrNull()
    private fun readInt(path: String): Int? = readText(path)?.toIntOrNull()

    /** mA→µA rescale heuristic. Kernel docs mandate µA but some OEMs
     *  (Samsung most notably) report mA in `current_*` fields. Treat
     *  any |x| < 100_000 as mA and multiply ×1000; everything else as
     *  the canonical µA. Same threshold as [BatteryChargerSpec]. */
    private fun normCurrentUa(raw: Long): Long =
        if (kotlin.math.abs(raw) < 100_000L) raw * 1000L else raw

    // ─────────────────────── battery ───────────────────────

    /** All readable fields under `/sys/class/power_supply/battery/`. */
    fun battery(): List<Pair<String, String>> {
        val base = "/sys/class/power_supply/battery"
        if (!File(base).exists()) return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        readText("$base/status")?.let         { rows += "status"          to it }
        readText("$base/health")?.let         { rows += "health"          to it }
        readText("$base/technology")?.let     { rows += "technology"      to it }
        readInt("$base/capacity")?.let        { rows += "capacity"        to "$it %" }
        readText("$base/capacity_level")?.let { rows += "capacity_level"  to it }
        readInt("$base/temp")?.let            { rows += "temp"            to "%.1f °C".format(it / 10.0) }
        readLong("$base/voltage_now")?.let    { rows += "voltage_now"     to "%.3f V".format(it / 1_000_000.0) }
        readLong("$base/voltage_max")?.let    { rows += "voltage_max"     to "%.3f V".format(it / 1_000_000.0) }
        readLong("$base/voltage_min")?.let    { rows += "voltage_min"     to "%.3f V".format(it / 1_000_000.0) }
        readLong("$base/current_now")?.let    {
            val ua = normCurrentUa(it)
            rows += "current_now" to "%+.0f mA".format(ua / 1000.0)
        }
        readLong("$base/current_max")?.let    {
            val ua = normCurrentUa(it)
            rows += "current_max" to "%.0f mA".format(ua / 1000.0)
        }
        readLong("$base/charge_now")?.let     { rows += "charge_now"      to "%d mAh".format(it / 1000L) }
        readLong("$base/charge_full")?.let    { rows += "charge_full"     to "%d mAh".format(it / 1000L) }
        readLong("$base/charge_full_design")?.let {
            rows += "charge_full_design" to "%d mAh".format(it / 1000L)
        }
        // Derived: battery wear% = 1 − full / design.
        val full = readLong("$base/charge_full")
        val design = readLong("$base/charge_full_design")
        if (full != null && design != null && design > 0L) {
            val healthPct = (full.toDouble() / design * 100.0).coerceIn(0.0, 200.0)
            val wearPct = (100.0 - healthPct).coerceAtLeast(0.0)
            rows += "wear (derived)" to "%.1f %% worn  (%.1f %% health)".format(wearPct, healthPct)
        }
        readLong("$base/charge_counter")?.let { rows += "charge_counter"  to "%d mAh".format(it / 1000L) }
        readInt("$base/cycle_count")?.let     { rows += "cycle_count"     to it.toString() }
        readLong("$base/time_to_empty_now")?.let { rows += "time_to_empty" to fmtSecs(it) }
        readLong("$base/time_to_full_now")?.let  { rows += "time_to_full"  to fmtSecs(it) }
        readLong("$base/energy_now")?.let     { rows += "energy_now"      to "%.2f Wh".format(it / 1_000_000.0) }
        readLong("$base/energy_full")?.let    { rows += "energy_full"     to "%.2f Wh".format(it / 1_000_000.0) }
        readLong("$base/power_now")?.let      {
            val w = kotlin.math.abs(it / 1_000_000.0)
            rows += "power_now" to "%.2f W".format(w)
        }
        readText("$base/manufacturer")?.let   { rows += "manufacturer"    to it }
        readText("$base/model_name")?.let     { rows += "model_name"      to it }
        readText("$base/serial_number")?.let  { rows += "serial_number"   to it }
        return rows
    }

    // ─────────────────────── chargers ───────────────────────

    /** Per-input charger telemetry. Walks `usb`, `ac`, `wireless`,
     *  `main` and skips nodes that don't exist on this device. */
    fun chargers(): List<Pair<String, String>> {
        val nodes = listOf("usb", "ac", "wireless", "main")
        val rows = mutableListOf<Pair<String, String>>()
        for (n in nodes) {
            val base = "/sys/class/power_supply/$n"
            if (!File(base).exists()) continue
            val online = readInt("$base/online")
            rows += "$n/online" to (online?.toString() ?: "—")
            readText("$base/type")?.let      { rows += "$n/type"      to it }
            readText("$base/usb_type")?.let  { rows += "$n/usb_type"  to it }
            readLong("$base/voltage_now")?.let {
                rows += "$n/voltage_now" to "%.3f V".format(it / 1_000_000.0)
            }
            readLong("$base/voltage_max")?.let {
                rows += "$n/voltage_max" to "%.2f V".format(it / 1_000_000.0)
            }
            readLong("$base/current_now")?.let {
                val ua = normCurrentUa(it)
                rows += "$n/current_now" to "%+.0f mA".format(ua / 1000.0)
            }
            readLong("$base/current_max")?.let {
                val ua = normCurrentUa(it)
                rows += "$n/current_max" to "%.0f mA".format(ua / 1000.0)
            }
            readLong("$base/input_current_limit")?.let {
                val ua = normCurrentUa(it)
                rows += "$n/input_current_limit" to "%.0f mA".format(ua / 1000.0)
            }
            // Derived live input power for this node.
            val vUv = readLong("$base/voltage_now")
            val iRaw = readLong("$base/current_now")
            if (vUv != null && iRaw != null && vUv > 0L && iRaw != 0L) {
                val ua = normCurrentUa(iRaw)
                val w = kotlin.math.abs((vUv * ua) / 1_000_000_000_000.0)
                if (w > 0.005) rows += "$n/live_power (derived)" to "%.2f W".format(w)
            }
        }
        return rows
    }

    // ─────────────────────── CPU load ───────────────────────

    /** Aggregate CPU-time snapshot read in one pass from `/proc/loadavg`
     *  + `/proc/uptime`. Times are in seconds (uptime + idle are
     *  cumulative since boot; loadavg is a unit-less moving avg of
     *  runnable+uninterruptible procs).
     *
     *  Note: `idleAggregateSec` is summed across ALL cores (the kernel
     *  multiplies an idle core's contribution by the time it was idle).
     *  `busyAggregateSec` is the complement: `uptime × cores − idle`.
     *  A single-core saturated chip with the other 7 idle would still
     *  show only ~12.5% busy here, which is what the user wants for a
     *  load-fraction read. */
    data class CpuLoad(
        val loadAvg1m: Double,
        val loadAvg5m: Double,
        val loadAvg15m: Double,
        val runnable: Int,
        val totalThreads: Int,
        val lastPid: Long,
        val uptimeSec: Double,
        val idleAggregateSec: Double,
        val cores: Int,
    ) {
        val busyAggregateSec: Double
            get() = (uptimeSec * cores - idleAggregateSec).coerceAtLeast(0.0)
        /** Fraction 0..1 of total core-seconds spent busy since boot. */
        val busyFraction: Double
            get() = if (uptimeSec > 0.0) (busyAggregateSec / (uptimeSec * cores)).coerceIn(0.0, 1.0) else 0.0
    }

    fun cpuLoad(): CpuLoad? {
        val cores = Runtime.getRuntime().availableProcessors()
        val loadav = readText("/proc/loadavg") ?: return null
        val parts = loadav.split(' ').filter { it.isNotBlank() }
        if (parts.size < 5) return null
        val avg1 = parts[0].toDoubleOrNull() ?: return null
        val avg5 = parts[1].toDoubleOrNull() ?: return null
        val avg15 = parts[2].toDoubleOrNull() ?: return null
        val rt = parts[3].split('/')
        val runnable = rt.getOrNull(0)?.toIntOrNull() ?: 0
        val total = rt.getOrNull(1)?.toIntOrNull() ?: 0
        val lastPid = parts[4].toLongOrNull() ?: 0L
        val uptime = readText("/proc/uptime")?.split(' ')?.filter { it.isNotBlank() }
        val uptimeSec = uptime?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val idleSec   = uptime?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return CpuLoad(avg1, avg5, avg15, runnable, total, lastPid, uptimeSec, idleSec, cores)
    }

    fun cpuLoadRows(): List<Pair<String, String>> {
        val c = cpuLoad() ?: return listOf("loadavg" to "—")
        return listOf(
            "loadavg"            to "%.2f · %.2f · %.2f".format(c.loadAvg1m, c.loadAvg5m, c.loadAvg15m),
            "loadavg periods"    to "1 m · 5 m · 15 m",
            "runnable / total"   to "${c.runnable} / ${c.totalThreads}",
            "last PID"           to c.lastPid.toString(),
            "uptime"             to "${fmtSecs(c.uptimeSec.toLong())}  (%.0f s)".format(c.uptimeSec),
            "idle (Σ cores)"     to "${fmtSecs(c.idleAggregateSec.toLong())}  (%.0f s)".format(c.idleAggregateSec),
            "busy (Σ cores)"     to "${fmtSecs(c.busyAggregateSec.toLong())}  (%.0f s)".format(c.busyAggregateSec),
            "busy fraction"      to "%.1f %%  (×%d cores)".format(c.busyFraction * 100.0, c.cores),
        )
    }

    // ─────────────────────── CPU per-core / freq ───────────────────────

    fun cpuFreqs(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val cores = Runtime.getRuntime().availableProcessors()
        for (i in 0 until cores) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val cur = readLong("$base/scaling_cur_freq")
            val min = readLong("$base/scaling_min_freq")
            val max = readLong("$base/scaling_max_freq")
            val gov = readText("$base/scaling_governor")
            if (cur == null && gov == null) continue
            val parts = mutableListOf<String>()
            cur?.let { parts += "cur %d MHz".format(it / 1000) }
            min?.let { parts += "min %d".format(it / 1000) }
            max?.let { parts += "max %d".format(it / 1000) }
            gov?.let { parts += "gov $it" }
            rows += "cpu$i" to parts.joinToString(" · ")
        }
        return rows
    }

    // ─────────────────────── /proc/stat (jiffies → seconds) ───────────────────────

    /** Aggregate `cpu` line of `/proc/stat`. Each field is a jiffy
     *  count; divide by `getClkTck()` for seconds. Returns null when
     *  the file is unreadable. */
    fun procStatRows(): List<Pair<String, String>> {
        val tck = clkTck()
        val txt = readText("/proc/stat") ?: return emptyList()
        val first = txt.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return emptyList()
        val cols = first.split(' ').filter { it.isNotBlank() }
        if (cols.size < 8) return emptyList()
        val labels = listOf("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal", "guest", "guest_nice")
        val rows = mutableListOf<Pair<String, String>>()
        // cols[0] = "cpu"; cols[1+] = jiffies
        for ((idx, name) in labels.withIndex()) {
            val j = cols.getOrNull(idx + 1)?.toLongOrNull() ?: continue
            rows += "stat/$name" to "%.1f s  (%d jiffies)".format(j.toDouble() / tck, j)
        }
        return rows
    }

    private fun clkTck(): Double {
        // sysconf(_SC_CLK_TCK). Hardcoded 100 = the Android-universal
        // default; if a future kernel changes it we'd want to JNI in,
        // but no production Android device deviates from 100Hz today.
        return 100.0
    }

    // ─────────────────────── thermal ───────────────────────

    fun thermal(): List<Pair<String, String>> {
        val zones = runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name }
        }.getOrNull() ?: return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        for (z in zones) {
            val type = runCatching { File(z, "type").readText().trim() }.getOrDefault(z.name)
            val tempMilliC = runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull() ?: continue
            rows += z.name to "$type — %.1f °C".format(tempMilliC / 1000.0)
        }
        return rows
    }

    // ─────────────────────── /proc/meminfo ───────────────────────

    fun memInfo(): List<Pair<String, String>> {
        val txt = readText("/proc/meminfo") ?: return emptyList()
        val want = listOf(
            "MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached",
            "SwapCached", "SwapTotal", "SwapFree",
            "Dirty", "Writeback", "Mapped", "Shmem",
            "Slab", "SReclaimable", "SUnreclaim",
            "PageTables", "VmallocTotal", "VmallocUsed",
        )
        val parsed = mutableMapOf<String, String>()
        for (line in txt.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            if (k !in want) continue
            val v = line.substring(idx + 1).trim()
            val kb = v.split(' ').firstOrNull { it.isNotBlank() }?.toLongOrNull()
            parsed[k] = if (kb != null) fmtKB(kb) else v
        }
        // Render in the order declared in `want` so the UI is stable.
        return want.mapNotNull { k -> parsed[k]?.let { k to it } }
    }

    // ─────────────────────── network ───────────────────────

    fun network(): List<Pair<String, String>> {
        val net = File("/sys/class/net")
        val ifs = runCatching { net.listFiles() }.getOrNull() ?: return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        for (i in ifs.sortedBy { it.name }) {
            val rx = readLong("${i.path}/statistics/rx_bytes")
            val tx = readLong("${i.path}/statistics/tx_bytes")
            if (rx == null && tx == null) continue
            val state = readText("${i.path}/operstate") ?: "?"
            rows += i.name to "$state · rx ${fmtBytes(rx ?: 0L)} · tx ${fmtBytes(tx ?: 0L)}"
        }
        return rows
    }

    // ─────────────────────── diskstats ───────────────────────

    fun diskstats(): List<Pair<String, String>> {
        val txt = readText("/proc/diskstats") ?: return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        for (raw in txt.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 14) continue
            val name = parts[2]
            // Skip ramdisks / loops — they're never load-bearing.
            if (name.startsWith("loop") || name.startsWith("ram")) continue
            // Fields per Linux diskstats spec: 3=reads, 5=read_sectors,
            // 7=writes, 9=write_sectors. Each sector = 512 B.
            val readSectors  = parts[5].toLongOrNull() ?: 0L
            val writeSectors = parts[9].toLongOrNull() ?: 0L
            if (readSectors == 0L && writeSectors == 0L) continue
            rows += name to "r ${fmtBytes(readSectors * 512L)} · w ${fmtBytes(writeSectors * 512L)}"
        }
        return rows
    }

    // ─────────────────────── self process ───────────────────────

    /** Our own `/proc/self/stat` + `/proc/self/status` digest. utime +
     *  stime are jiffies; rss is in pages (assume 4 kB, the Android
     *  universal). The `(comm)` field may contain spaces inside the
     *  parens, so split from the LAST `)` instead of the first space. */
    fun selfProc(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val stat = readText("/proc/self/stat")
        if (stat != null) {
            val close = stat.lastIndexOf(')')
            if (close > 0) {
                val tail = stat.substring(close + 2)
                    .split(' ').filter { it.isNotBlank() }
                // tail[0..N] mapped to /proc/<pid>/stat fields 3..N+3
                // (the man page numbers from 1; we subtract 2 since we
                // already consumed pid + comm). utime=tail[11],
                // stime=tail[12], num_threads=tail[17], rss=tail[21].
                val utime = tail.getOrNull(11)?.toLongOrNull() ?: 0L
                val stime = tail.getOrNull(12)?.toLongOrNull() ?: 0L
                val threads = tail.getOrNull(17)?.toLongOrNull() ?: 0L
                val rssPages = tail.getOrNull(21)?.toLongOrNull() ?: 0L
                val tck = clkTck()
                rows += "self/utime" to "%.2f s  (%d jiffies)".format(utime / tck, utime)
                rows += "self/stime" to "%.2f s  (%d jiffies)".format(stime / tck, stime)
                rows += "self/cpu (u+s)" to "%.2f s".format((utime + stime) / tck)
                rows += "self/threads" to threads.toString()
                rows += "self/rss" to fmtBytes(rssPages * 4096L)
            }
        }
        // Open FDs — directory entry count.
        runCatching { File("/proc/self/fd").listFiles()?.size }.getOrNull()?.let {
            rows += "self/fds" to it.toString()
        }
        return rows
    }

    // ─────────────────────── formatting ───────────────────────

    private fun fmtSecs(s: Long): String {
        if (s <= 0L) return "0 s"
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d m".format(h, m)
            m > 0 -> "%d m %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }

    private fun fmtKB(kb: Long): String = fmtBytes(kb * 1024L)

    private fun fmtBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L     -> "%.2f MB".format(b / 1_048_576.0)
        b >= 1024L          -> "%.1f kB".format(b / 1024.0)
        else                -> "$b B"
    }
}
