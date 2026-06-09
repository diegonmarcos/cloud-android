package com.diegonmarcos.superapp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shared battery-session reader + formatter. Single source of truth
 * for the rows surfaced in Configs/About/Battery & Usage AND in the
 * status-strip BatteryEstimatePopup (tap the battery icon).
 *
 * Two anchors live in SharedPreferences "battery_session":
 *   • unplug_ts / unplug_pct — recorded on disconnect, used to derive
 *     discharge rate + ETA until 0%.
 *   • plug_ts   / plug_pct   — recorded on connect, used to derive
 *     charge rate + ETA until 100%.
 *
 * Lifecycle:
 *   • Charging detected, plug anchor missing or curPct < plugPct (cable
 *     unplugged + replugged at a lower %) → record (now, curPct).
 *   • Charging detected → clear the stale discharge anchor (next unplug
 *     starts fresh).
 *   • Discharging detected → mirror behaviour for the discharge anchor;
 *     clear the charge anchor on first discharge read.
 *
 * read() is idempotent + side-effecting (it maintains both anchors) so
 * any caller can drive the lifecycle just by reading.
 *
 * Wall-clock ETAs use the system clock at read() time; callers that
 * cache the snapshot must re-read to keep the "ETA at HH:MM" rolling.
 */
object BatterySessionStats {

    /** Snapshot of one read pass — every field a UI surface might
     *  need without reaching back into the sticky intent. */
    data class Snapshot(
        val isCharging: Boolean,
        val curPct: Int,            // -1 if level/scale invalid
        val nowMs: Long,            // System.currentTimeMillis() at read

        // Discharge session (anchored at unplug)
        val unplugTs: Long,         // 0 if no anchor
        val unplugPct: Int,         // -1 if no anchor
        val elapsedMs: Long,        // since unplug, 0 if no anchor
        val consumedPct: Int,       // unplugPct - curPct, 0 if no anchor
        val ratePerMin: Double,     // discharge %/min, 0 if elapsedMin < 0.5
        val etaMs: Long,            // until 0%, -1 if rate too low / charging
        val etaDrainedAt: Long,     // nowMs + etaMs, -1 if etaMs < 0

        // Charging session (anchored at plug)
        val plugTs: Long,           // 0 if no anchor
        val plugPct: Int,           // -1 if no anchor
        val chargeElapsedMs: Long,  // since plug, 0 if no anchor
        val gainedPct: Int,         // curPct - plugPct, 0 if no anchor
        val chargeRatePerMin: Double, // charge %/min, 0 if elapsedMin < 0.5
        val etaFullMs: Long,        // until 100%, -1 if rate too low / discharging
        val etaFullAt: Long,        // nowMs + etaFullMs, -1 if etaFullMs < 0

        // Instantaneous power readings (BatteryManager + sticky intent)
        val voltageMv: Int,         // EXTRA_VOLTAGE in millivolts; -1 if unavailable
        val currentUa: Int,         // BATTERY_PROPERTY_CURRENT_NOW in microamps;
                                    // sign convention is OEM-specific (Samsung:
                                    // positive=discharging, negative=charging).
                                    // 0 if unavailable.
        val powerW: Double,         // |voltage × current| in watts; 0 if either
                                    // primary reading is invalid. This is the
                                    // BATTERY storage rate (V × I going INTO
                                    // the cell), NOT the charger input — Android
                                    // doesn't expose the latter publicly.

        // Charger spec via dumpsys battery (needs android.permission.DUMP,
        // typically granted via `adb shell pm grant ... DUMP` on debuggable
        // APKs). All-zero / empty source on permission denial → renders "—".
        val chargerSpec: BatteryChargerSpec.Spec,
    )

    fun read(ctx: Context, now: Long = System.currentTimeMillis()): Snapshot {
        val sticky = ctx.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val curLevel  = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)  ?: -1
        val curScale  = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)  ?: -1
        val curStatus = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val curPct = if (curLevel >= 0 && curScale > 0) curLevel * 100 / curScale else -1
        val isCharging = curStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            curStatus == BatteryManager.BATTERY_STATUS_FULL

        val sp = ctx.getSharedPreferences("battery_session", Context.MODE_PRIVATE)
        var unplugTs  = sp.getLong("unplug_ts", 0L)
        var unplugPct = sp.getInt("unplug_pct", -1)
        var plugTs    = sp.getLong("plug_ts", 0L)
        var plugPct   = sp.getInt("plug_pct", -1)

        if (isCharging) {
            // Stale discharge anchor — drop it so the next unplug starts
            // clean. PowerStateReceiver will mint a fresh one on the
            // ACTION_POWER_DISCONNECTED broadcast.
            if (unplugTs != 0L) {
                sp.edit().remove("unplug_ts").remove("unplug_pct").remove("anchor_source").apply()
                unplugTs = 0L; unplugPct = -1
            }
            // Anchor the charge session — first read while plugged OR if
            // pct went DOWN since the recorded plug anchor (cable was
            // briefly unplugged while we were paused).
            if (curPct in 0..100) {
                val haveAnchor = plugTs != 0L && plugPct in 0..100
                val anchorRose = haveAnchor && curPct < plugPct
                if (!haveAnchor || anchorRose) {
                    sp.edit()
                        .putLong("plug_ts", now)
                        .putInt("plug_pct", curPct)
                        .apply()
                    plugTs = now; plugPct = curPct
                }
            }
        } else if (curPct in 0..100) {
            // Drop the plug anchor (we're no longer charging).
            if (plugTs != 0L) {
                sp.edit().remove("plug_ts").remove("plug_pct").apply()
                plugTs = 0L; plugPct = -1
            }
            val haveAnchor = unplugTs != 0L && unplugPct in 0..100
            val anchorRose = haveAnchor && curPct > unplugPct
            if (!haveAnchor || anchorRose) {
                sp.edit()
                    .putLong("unplug_ts", now)
                    .putInt("unplug_pct", curPct)
                    .putString("anchor_source", "first_read_fallback")
                    .apply()
                unplugTs = now; unplugPct = curPct
            }
        }

        val elapsedMs = if (unplugTs > 0L) (now - unplugTs).coerceAtLeast(0L) else 0L
        val consumed  = if (unplugPct in 0..100 && curPct in 0..100)
            (unplugPct - curPct).coerceAtLeast(0) else 0
        val elapsedMin = elapsedMs / 60_000.0
        val rate = if (elapsedMin > 0.5) consumed / elapsedMin else 0.0
        val etaMs = if (!isCharging && rate > 0.001 && curPct >= 0)
            ((curPct / rate) * 60_000.0).toLong() else -1L
        val etaDrainedAt = if (etaMs > 0L) now + etaMs else -1L

        val chargeElapsedMs = if (plugTs > 0L) (now - plugTs).coerceAtLeast(0L) else 0L
        val gained = if (plugPct in 0..100 && curPct in 0..100)
            (curPct - plugPct).coerceAtLeast(0) else 0
        val chargeElapsedMin = chargeElapsedMs / 60_000.0
        val chargeRate = if (chargeElapsedMin > 0.5) gained / chargeElapsedMin else 0.0
        val remainingPct = if (curPct in 0..100) (100 - curPct) else -1
        val etaFullMs = if (isCharging && chargeRate > 0.001 && remainingPct > 0)
            ((remainingPct / chargeRate) * 60_000.0).toLong() else -1L
        val etaFullAt = if (etaFullMs > 0L) now + etaFullMs else -1L

        // Instantaneous power. Voltage comes from the sticky intent
        // (millivolts), current from BatteryManager.getIntProperty.
        // Android docs say µA — Samsung (and a few other OEMs) deviate
        // and return mA, so a 3 A charge shows up as raw=3000 instead
        // of 3_000_000. Without rescaling, P = V × I / 1e9 yields ~13 mW
        // instead of ~13 W (user reported "0 mW" while charging at
        // 10-45 W). Heuristic: any plausible phone draw / input is
        // 100 mA - 6 A, i.e. raw 100k–6M when reported in µA. If the
        // absolute raw value falls outside that band but inside the
        // milliamp band (1–6000), rescale ×1000 to fold it back into µA.
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val currentRaw = runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        }.getOrDefault(0)
        val currentUa = when {
            currentRaw == 0 || currentRaw == Int.MIN_VALUE -> 0
            // |raw| < 50_000 means the OEM is using mA (5 A in mA is
            // 5000, in µA it would be 5_000_000). Rescale to µA.
            kotlin.math.abs(currentRaw) < 50_000           -> currentRaw * 1000
            else                                           -> currentRaw
        }
        val powerW = if (voltageMv > 0 && currentUa != 0)
            kotlin.math.abs((voltageMv.toLong() * currentUa.toLong()) / 1_000_000_000.0)
        else 0.0

        // Charger spec — only meaningful when isCharging; reading it
        // when discharging would return zeros (no max negotiated).
        // Use named args — Spec gains fields over time (we added
        // liveInputW + source for the sysfs reader). Positional would
        // silently desync the next time Spec grows.
        val chargerSpec = if (isCharging) BatteryChargerSpec.read()
                          else BatteryChargerSpec.Spec(
                              maxCurrentUa    = 0,
                              maxVoltageUv    = 0,
                              maxPowerW       = 0.0,
                              liveInputW      = 0.0,
                              source          = "",
                              usbPowered      = false,
                              acPowered       = false,
                              wirelessPowered = false,
                          )

        return Snapshot(
            isCharging       = isCharging,
            curPct           = curPct,
            nowMs            = now,
            unplugTs         = unplugTs,
            unplugPct        = unplugPct,
            elapsedMs        = elapsedMs,
            consumedPct      = consumed,
            ratePerMin       = rate,
            etaMs            = etaMs,
            etaDrainedAt     = etaDrainedAt,
            plugTs           = plugTs,
            plugPct          = plugPct,
            chargeElapsedMs  = chargeElapsedMs,
            gainedPct        = gained,
            chargeRatePerMin = chargeRate,
            etaFullMs        = etaFullMs,
            etaFullAt        = etaFullAt,
            voltageMv        = voltageMv,
            currentUa        = currentUa,
            powerW           = powerW,
            chargerSpec      = chargerSpec,
        )
    }

    /** Format the charger spec row. Prefers the LIVE sysfs reading
     *  (`/sys/class/power_supply/usb/*` etc., no perm needed); falls
     *  back to the negotiated MAX via dumpsys (needs DUMP); falls back
     *  to "—" when nothing readable. Shape: "14.7 W (USB) live" /
     *  "25.5 W (USB) max" / "—". */
    fun fmtChargerSpec(s: Snapshot): String {
        val spec = s.chargerSpec
        val src = spec.sourceLabel().ifEmpty { "—" }
        return when {
            spec.liveInputW > 0.005 -> {
                val p = if (spec.liveInputW < 1.0) "%.0f mW".format(spec.liveInputW * 1000.0)
                        else "%.1f W".format(spec.liveInputW)
                "$p  ($src) live"
            }
            spec.maxPowerW > 0.005 -> {
                val p = if (spec.maxPowerW < 1.0) "%.0f mW".format(spec.maxPowerW * 1000.0)
                        else "%.1f W".format(spec.maxPowerW)
                "$p  ($src) max"
            }
            else -> "—"
        }
    }

    /** Phone consumption estimate = charger_live − battery_storage.
     *  Only meaningful when BOTH readings are available (sysfs live
     *  input + V × I battery). Empty string otherwise so callers fall
     *  back to "—". */
    fun fmtPhoneConsumption(s: Snapshot): String {
        val live = s.chargerSpec.liveInputW
        val storage = s.powerW
        if (live <= 0.005 || storage <= 0.005) return ""
        val delta = (live - storage).coerceAtLeast(0.0)
        if (delta <= 0.005) return ""
        return if (delta < 1.0) "%.0f mW".format(delta * 1000.0)
               else             "%.1f W".format(delta)
    }

    // ── Discharge-flavoured formatters (existing — unchanged callers) ──
    fun fmtSinceLastCharge(s: Snapshot): String = when {
        s.isCharging -> "Charging — n/a"
        s.unplugTs <= 0L || s.unplugPct < 0 || s.curPct < 0 -> "Anchoring on next render…"
        else -> "${fmtDuration(s.elapsedMs)}  ·  −${s.consumedPct}%  (${s.unplugPct}% → ${s.curPct}%)"
    }

    fun fmtRate(s: Snapshot): String = when {
        s.isCharging -> "—"
        s.ratePerMin > 0.001 -> appendPower(s, "%.2f%%/h".format(s.ratePerMin * 60.0))
        else -> "Computing…"
    }

    /** Append " | <power>" to a rate string when the instantaneous
     *  power reading is available, so the user sees both the time-
     *  derived rate AND the on-device power draw (or input) side-by-
     *  side. Sub-watt values render as mW so idle-screen draws (≈30-
     *  100 mW) are still visible. */
    private fun appendPower(s: Snapshot, rateStr: String): String {
        val p = fmtPowerOnly(s)
        return if (p.isNotEmpty()) "$rateStr | $p" else rateStr
    }

    /** Standalone power formatter. Returns "" when no reading is
     *  available OR rounds to "0 mW" (Samsung returns 0 / sub-mA when
     *  the screen is off or the reading hasn't refreshed). Watts
     *  above 1 W → "12.6 W"; sub-watt active draw → "850 mW"; under
     *  ~5 mW noise → "". Callers render "—" on empty so the row
     *  never disappears.  */
    fun fmtPowerOnly(s: Snapshot): String = when {
        s.powerW <= 0.005 -> ""                                // noise floor / Samsung 0-reading
        s.powerW < 1.0    -> "%.0f mW".format(s.powerW * 1000.0)
        else              -> "%.2f W".format(s.powerW)
    }

    /** Dedicated row formatter — shows the power + direction prefix.
     *  Empty reading falls back to "—" so the row never disappears. */
    fun fmtPowerRow(s: Snapshot): String {
        val p = fmtPowerOnly(s)
        if (p.isEmpty()) return "—"
        val arrow = if (s.isCharging) "↑ in" else "↓ out"
        return "$p  $arrow"
    }

    fun fmtEta(s: Snapshot): String = when {
        s.isCharging -> "—"
        s.etaMs > 0L -> fmtDuration(s.etaMs)
        else -> "Computing…"
    }

    // ── Unified formatters (auto-flip on charging) ────────────────────
    /** Header / "since" row that swaps wording on charging. */
    fun fmtSinceAnchor(s: Snapshot): String = when {
        s.isCharging -> when {
            s.plugTs <= 0L || s.plugPct < 0 || s.curPct < 0 -> "Anchoring on next render…"
            else -> "${fmtDuration(s.chargeElapsedMs)}  ·  +${s.gainedPct}%  (${s.plugPct}% → ${s.curPct}%)"
        }
        else -> fmtSinceLastCharge(s)
    }

    /** Rate row that swaps consumed/gained sign on charging.
     *  Units = %/h (per-hour); we multiply the per-minute internal rate
     *  by 60 here so callers can read a meaningful "X% per hour" number
     *  instead of a tiny "0.034%/min". Appends " | X.XW" instantaneous
     *  power when available (BatteryManager.CURRENT_NOW × EXTRA_VOLTAGE). */
    fun fmtRateUnified(s: Snapshot): String = when {
        s.isCharging -> when {
            s.chargeRatePerMin > 0.001 ->
                appendPower(s, "+%.2f%%/h".format(s.chargeRatePerMin * 60.0))
            else -> "Computing…"
        }
        else -> when {
            s.ratePerMin > 0.001 ->
                appendPower(s, "−%.2f%%/h".format(s.ratePerMin * 60.0))
            else -> "Computing…"
        }
    }

    /** Duration ETA — until 0% when discharging, until 100% when charging. */
    fun fmtEtaDuration(s: Snapshot): String = when {
        s.isCharging -> when {
            s.curPct >= 100 -> "Fully charged"
            s.etaFullMs > 0L -> fmtDuration(s.etaFullMs)
            else -> "Computing…"
        }
        else -> fmtEta(s)
    }

    /** Wall-clock ETA — clock time when the battery hits 0% (discharging)
     *  or 100% (charging). Renders as "HH:mm" with "today" / "tomorrow"
     *  qualifier so the user can read it at a glance. */
    fun fmtEtaWallClock(s: Snapshot): String {
        if (s.isCharging && s.curPct >= 100) return "Fully charged"
        val tsMs: Long = if (s.isCharging) s.etaFullAt else s.etaDrainedAt
        if (tsMs <= 0L) return "Computing…"
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = fmt.format(Date(tsMs))
        val qualifier = dayQualifier(s.nowMs, tsMs)
        return "$time $qualifier"
    }

    private fun dayQualifier(nowMs: Long, tsMs: Long): String {
        val cNow = Calendar.getInstance().apply { timeInMillis = nowMs }
        val cTs  = Calendar.getInstance().apply { timeInMillis = tsMs }
        val nowDay = cNow.get(Calendar.DAY_OF_YEAR) + cNow.get(Calendar.YEAR) * 1000
        val tsDay  = cTs .get(Calendar.DAY_OF_YEAR) + cTs .get(Calendar.YEAR) * 1000
        val delta = tsDay - nowDay
        return when (delta) {
            0    -> "today"
            1    -> "tomorrow"
            in 2..6 -> "in $delta days"
            else -> "in $delta days"
        }
    }

    fun fmtDuration(ms: Long): String {
        if (ms < 0) return "—"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }
}
