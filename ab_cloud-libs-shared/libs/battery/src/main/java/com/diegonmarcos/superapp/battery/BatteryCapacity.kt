package com.diegonmarcos.superapp.battery

import android.content.Context
import java.io.File

/**
 * Battery capacity reader — surfaces the RATED (nameplate / design)
 * capacity and the CURRENT-FULL (aged) capacity, each in mAh plus a
 * derived Watt-hour figure.
 *
 * Why a dedicated reader: Android exposes NO public API for design
 * capacity, and on hardened Samsung One UI 7 the sysfs
 * `/sys/class/power_supply/battery/charge_full_design` node is
 * SELinux-blocked (see EnergyUsageDialog — "Design isn't exposed by
 * Android"). The reliable cross-device path is the hidden framework
 * PowerProfile.getBatteryCapacity() via reflection — the same trick
 * AccuBattery uses — which returns design capacity in mAh and is
 * permission-free on virtually every device, Samsung included. It can
 * still return null on the newest hidden-API-hardened builds; we then
 * fall back to sysfs and finally render "—".
 *
 * Watt-hour is computed as mAh × [NOMINAL_VOLTAGE_V] (single-cell
 * Li-ion nominal = 3.85 V). We deliberately do NOT read sysfs
 * `energy_full` (also usually blocked) nor the live voltage (it swings
 * 3.3–4.4 V and would make the Wh figure jump every read) — a fixed
 * nominal keeps the number stable and consistent with how capacity is
 * rated on the spec sheet.
 *
 * KDoc gotcha (same as SysfsProc.kt / BatterySessionStats.kt): never
 * write a literal slash-star inside this block comment — Kotlin block
 * comments are nested-aware, so it would open a nested comment and
 * swallow the rest of the file. Use `...` or `<node>` as placeholders.
 */
object BatteryCapacity {

    /** Single-cell Li-ion/Li-Po nominal voltage. Capacity (mAh) × this
     *  ÷ 1000 = energy (Wh). 3.85 V is the industry-standard nominal
     *  used on phone battery spec sheets. */
    const val NOMINAL_VOLTAGE_V = 3.85

    /** mAh fields are -1 when no source could supply them. */
    data class Capacity(
        val ratedMah: Int,    // design / nameplate capacity, -1 if unknown
        val fullNowMah: Int,  // present full-charge capacity (worn), -1 if unknown
    ) {
        val ratedWh: Double   get() = if (ratedMah   > 0) ratedMah   * NOMINAL_VOLTAGE_V / 1000.0 else -1.0
        val fullNowWh: Double get() = if (fullNowMah > 0) fullNowMah * NOMINAL_VOLTAGE_V / 1000.0 else -1.0
    }

    /**
     * @param peakChargeCounterUah the empirical full capacity captured
     *   at the last 100 % reading (BatterySessionStats tracks it via
     *   BATTERY_PROPERTY_CHARGE_COUNTER). 0 when uncalibrated → we fall
     *   back to sysfs `charge_full`.
     */
    fun read(ctx: Context, peakChargeCounterUah: Long): Capacity =
        Capacity(
            ratedMah   = ratedMah(ctx),
            fullNowMah = fullNowMah(peakChargeCounterUah),
        )

    /** Rated/design capacity in mAh. Priority: PowerProfile reflection
     *  (works on Samsung, no perm) → sysfs `charge_full_design` (usually
     *  blocked on One UI 7 but free where readable). -1 when neither. */
    private fun ratedMah(ctx: Context): Int {
        powerProfileCapacityMah(ctx)?.let { if (it > 0) return it }
        readSysfsMah("/sys/class/power_supply/battery/charge_full_design")?.let { if (it > 0) return it }
        return -1
    }

    /** Current full-charge capacity in mAh. Priority: empirical peak
     *  from CHARGE_COUNTER (BatterySessionStats) → sysfs `charge_full`.
     *  -1 when uncalibrated AND sysfs blocked. */
    private fun fullNowMah(peakChargeCounterUah: Long): Int {
        if (peakChargeCounterUah > 0L) return (peakChargeCounterUah / 1000L).toInt()
        readSysfsMah("/sys/class/power_supply/battery/charge_full")?.let { if (it > 0) return it }
        return -1
    }

    /** Hidden `com.android.internal.os.PowerProfile.getBatteryCapacity()`
     *  via reflection. Returns design capacity in mAh, or null when the
     *  class/method is missing or throws (hidden-API blocklist on the
     *  newest builds). AccuBattery's path — permission-free on most
     *  devices, Samsung included. */
    private fun powerProfileCapacityMah(ctx: Context): Int? = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val instance = cls.getConstructor(Context::class.java).newInstance(ctx)
        val mAh = cls.getMethod("getBatteryCapacity").invoke(instance) as? Double
        mAh?.takeIf { it > 0.0 }?.toInt()
    }.getOrNull()

    /** Read a sysfs µAh node → mAh. null when unreadable (SELinux) or
     *  not a positive integer. */
    private fun readSysfsMah(path: String): Int? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L }?.let { (it / 1000L).toInt() }
    }.getOrNull()

    // ─────────────────────── formatting ───────────────────────

    /** "4000 mAh  (15.4 Wh)" or "—" when the rated capacity is unknown
     *  (PowerProfile blocked + sysfs blocked). */
    fun fmtRated(c: Capacity): String =
        if (c.ratedMah > 0) "%d mAh  (%.1f Wh)".format(c.ratedMah, c.ratedWh) else "—"

    /** "3784 mAh  (14.6 Wh)" or a calibrating hint when the empirical
     *  peak isn't captured yet and sysfs `charge_full` is blocked. */
    fun fmtFullNow(c: Capacity): String =
        if (c.fullNowMah > 0) "%d mAh  (%.1f Wh)".format(c.fullNowMah, c.fullNowWh)
        else "calibrating — charge to 100 %"
}
