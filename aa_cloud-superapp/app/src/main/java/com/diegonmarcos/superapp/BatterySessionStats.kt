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
                                    // primary reading is invalid.
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
        // (millivolts), current from BatteryManager.getIntProperty
        // (microamps). Power = |I × V|; sign discarded since the
        // isCharging flag already conveys direction. Either reading
        // missing → powerW = 0.0 so callers can render "—".
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val currentUa = runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        }.getOrDefault(0)
        val powerW = if (voltageMv > 0 && currentUa != 0 && currentUa != Int.MIN_VALUE)
            kotlin.math.abs((voltageMv.toLong() * currentUa.toLong()) / 1_000_000_000.0)
        else 0.0

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
        )
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

    /** Append " | X.XW" to a rate string when the instantaneous power
     *  reading is available, so the user sees both the time-derived
     *  rate AND the on-device power draw (or input) side-by-side. */
    private fun appendPower(s: Snapshot, rateStr: String): String =
        if (s.powerW > 0.05) "$rateStr | %.1fW".format(s.powerW) else rateStr

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
