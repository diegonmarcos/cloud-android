package com.diegonmarcos.superapp.battery

import android.content.Context
import android.os.PowerManager

/**
 * Power IN / OUT breakdown — the AccuBattery-style "where are the watts
 * going" model, shared by the Battery Usage Details screen and the
 * status-bar battery popup.
 *
 *   ACTUAL IN (wall)  =  NET (to battery)  +  CONSUMPTION (phone)
 *
 * Data reality on this device (S21, One UI 7): the true charger/wall input
 * power is NOT readable — `max_charging_*` come back null and
 * the /sys/class/power_supply/usb/ nodes are SELinux-blocked even for shell.
 * So:
 *   • NET is MEASURED directly  (battery V × I, signed).
 *   • CONSUMPTION is MEASURED when discharging (battery supplies everything,
 *     so consumption == net out), and MODELED (estimate) when charging,
 *     from the Tier-1 [EnergyWatchdog] device-draw model.
 *   • ACTUAL IN is therefore DERIVED (NET + CONSUMPTION) and flagged "est"
 *     while charging; 0 while on battery.
 */
object PowerFlow {

    data class Flow(
        val charging: Boolean,
        /** Signed battery power: + into the cell (charging), − out (discharging). W. */
        val netW: Double,
        /** Phone system draw. W. */
        val consumptionW: Double,
        /** Derived wall input (charging only; 0 on battery). W. */
        val estInW: Double,
        /** "measured" | "modeled" | "unknown" — provenance of consumptionW. */
        val consumptionSource: String,
        val voltageV: Double,
    )

    fun read(ctx: Context): Flow {
        val s = BatterySessionStats.read(ctx)
        val mag = s.powerW // positive magnitude of battery V×I
        val v = if (s.voltageMv > 0) s.voltageMv / 1000.0 else 0.0
        return if (!s.isCharging) {
            // Discharging: the battery is the only source, so the phone's
            // consumption equals the net draw out of the cell — MEASURED.
            Flow(false, -mag, mag, 0.0, "measured", v)
        } else {
            val (cons, src) = modeledConsumptionW(ctx, v)
            Flow(true, +mag, cons, mag + cons, src, v)
        }
    }

    /** Estimate phone consumption while charging from the watchdog model:
     *  idle baseline + screen-on marginal (if the screen is on) × voltage. */
    private fun modeledConsumptionW(ctx: Context, v: Double): Pair<Double, String> {
        if (v <= 0) return 0.0 to "unknown"
        val attr = runCatching { EnergyWatchdog.attribution(ctx) }.getOrDefault(emptyMap())
        val samples = (attr["samples"] as? Int) ?: 0
        if (samples <= 0) return 0.0 to "unknown"
        var ma = (attr["baseline_idle_ma"] as? Int) ?: 0
        @Suppress("UNCHECKED_CAST")
        val states = attr["states"] as? Map<String, Map<String, Any>>
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == true) {
            ma += (states?.get("screen_on")?.get("marginal_ma") as? Int) ?: 0
        }
        return (ma * v / 1000.0) to "modeled"
    }

    // ── formatters ──────────────────────────────────────────────────
    fun fmtNet(f: Flow): String = when {
        f.netW == 0.0 -> "—"
        f.netW > 0 -> "+%.1f W → battery".format(f.netW)
        else -> "−%.1f W ← battery".format(kotlin.math.abs(f.netW))
    }

    fun fmtConsumption(f: Flow): String = when {
        f.consumptionW <= 0.0 -> if (f.consumptionSource == "unknown") "— (collecting)" else "—"
        f.consumptionSource == "modeled" -> "%.1f W (est)".format(f.consumptionW)
        else -> "%.1f W".format(f.consumptionW)
    }

    fun fmtEstIn(f: Flow): String = when {
        !f.charging -> "0 W (on battery)"
        f.estInW <= 0.0 -> "—"
        else -> "≈ %.1f W".format(f.estInW)
    }
}
