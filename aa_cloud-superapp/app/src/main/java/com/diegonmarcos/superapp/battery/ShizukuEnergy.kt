package com.diegonmarcos.superapp.battery
import com.diegonmarcos.superapp.system.ShizukuUserService

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku-backed EXACT per-app energy attribution (Tier 2) — the
 * ground-truth layer the Tier-1 correlation watchdog calibrates against.
 *
 * A normal app can't read [android.os.BatteryStatsManager] (privileged
 * BATTERY_STATS). Shizuku gives us a shell-uid (2000) process via a
 * bound [ShizukuUserService]; from there `dumpsys batterystats --charged`
 * exposes the same per-UID mAh estimate Settings/AccuBattery show. We
 * parse its "Estimated power use (mAh)" section and map each UID to its
 * app label.
 *
 * All entry points are null/false-safe: when Shizuku isn't installed,
 * isn't running, or isn't granted, callers get a clear status instead of
 * a crash, and the UI falls back to the Tier-1 correlation proxy.
 */
object ShizukuEnergy {

    const val PERMISSION_REQUEST_CODE = 6071

    @Volatile private var service: IUserService? = null
    @Volatile private var binding = false

    private val componentName: ComponentName
        get() = ComponentName(
            BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder())
                IUserService.Stub.asInterface(binder) else null
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(componentName)
            .daemon(false)
            .processNameSuffix("energy")
            .debuggable(false)
            .version(1)

    /** Shizuku app installed AND its service running (binder alive). */
    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /** Human status line for the UI. */
    fun status(): String = when {
        !isAvailable() -> "Shizuku not running"
        !isGranted()   -> "Shizuku running — permission not granted"
        service != null -> "Connected"
        else -> "Granted — binding…"
    }

    /** Bind the UserService (idempotent). No-op when unavailable or not
     *  granted. Async — [exact] returns null until the bind lands. */
    fun bind() {
        if (service != null || binding) return
        if (!isAvailable() || !isGranted()) return
        binding = true
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { binding = false }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        binding = false
    }

    private fun exec(cmd: String): String? =
        runCatching { service?.exec(cmd) }.getOrNull()?.takeIf { !it.startsWith("ERR:") }

    data class AppEnergy(val pkg: String, val label: String, val uid: Int, val mAh: Double)

    /** Exact per-app mAh from `dumpsys batterystats --charged`. Returns
     *  null when Shizuku is unavailable / not yet bound; empty list when
     *  the dump had no per-uid power section. Newest-since-charge view. */
    fun exact(ctx: Context): List<AppEnergy>? {
        if (service == null) { bind(); return null }
        val dump = exec("dumpsys batterystats --charged") ?: return null
        return parseEstimatedPower(ctx, dump)
    }

    /**
     * Parse the "Estimated power use (mAh):" block. Lines look like:
     *   Uid u0a123: 45.6 ( cpu=30.0 wifi=10.0 ... )
     *   Uid 1000: 12.3 ...
     *   Screen: 88.0
     *   Cell standby: 5.0
     * We keep the `Uid …` rows (app-attributable) and resolve the uid to
     * a package label; system rows (Screen/Idle/Cell) are skipped here —
     * those belong to the Tier-1 device-state view.
     */
    private fun parseEstimatedPower(ctx: Context, dump: String): List<AppEnergy> {
        val out = ArrayList<AppEnergy>()
        val pm = ctx.packageManager
        var inSection = false
        val uidLine = Regex("""^\s*Uid\s+(u(\d+)a(\d+)|\d+):\s*([\d.]+)""")
        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            if (!inSection) {
                if (line.contains("Estimated power use (mAh)")) inSection = true
                continue
            }
            // Section ends at the next blank line or a non-indented header.
            if (line.isBlank()) break
            val m = uidLine.find(line) ?: continue
            val token = m.groupValues[1]
            val mAh = m.groupValues[4].toDoubleOrNull() ?: continue
            val uid = when {
                token.startsWith("u") -> {
                    // u<user>a<appId> → uid = user*100000 + 10000 + appId
                    val user = m.groupValues[2].toIntOrNull() ?: 0
                    val appId = m.groupValues[3].toIntOrNull() ?: 0
                    user * 100000 + 10000 + appId
                }
                else -> token.toIntOrNull() ?: continue
            }
            val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
            val pkg = pkgs?.firstOrNull() ?: "uid:$uid"
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            out.add(AppEnergy(pkg, label, uid, mAh))
        }
        return out.sortedByDescending { it.mAh }
    }
}
