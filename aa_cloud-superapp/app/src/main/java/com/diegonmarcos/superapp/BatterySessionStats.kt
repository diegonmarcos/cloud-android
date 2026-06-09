package com.diegonmarcos.superapp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Shared battery-session reader + formatter. Single source of truth
 * for the three rows surfaced in Configs/About/Battery & Usage AND
 * in the status-strip BatteryEstimatePopup (tap the battery icon).
 *
 * Anchor lifecycle (SharedPreferences "battery_session"):
 *   • Charging          → clear stale anchor (next unplug starts fresh).
 *   • Discharging       → if no anchor or curPct > unplugPct (user
 *                         briefly plugged in while we were paused),
 *                         record (now, curPct).
 *
 * read() is idempotent + side-effecting (it maintains the anchor
 * pref) so any caller can drive the lifecycle just by reading.
 */
object BatterySessionStats {

    /** Snapshot of one read pass — every field a UI surface might
     *  need without reaching back into the sticky intent. */
    data class Snapshot(
        val isCharging: Boolean,
        val curPct: Int,        // -1 if level/scale invalid
        val unplugTs: Long,     // 0 if no anchor
        val unplugPct: Int,     // -1 if no anchor
        val elapsedMs: Long,    // 0 if no anchor
        val consumedPct: Int,   // 0 if no anchor / non-positive
        val ratePerMin: Double, // 0 if elapsedMin < 0.5
        val etaMs: Long,        // -1 if rate too low / charging
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

        // The anchor IS authoritative when PowerStateReceiver wrote it
        // (anchor_source == "disconnect_event") — that's the actual
        // moment the cable was pulled, regardless of whether the app
        // was running. Falls back to first-read approximation only
        // when no disconnect event has fired since last clear (fresh
        // install on a phone already off-cable, or pref cleared).
        val sp = ctx.getSharedPreferences("battery_session", Context.MODE_PRIVATE)
        var unplugTs  = sp.getLong("unplug_ts", 0L)
        var unplugPct = sp.getInt("unplug_pct", -1)
        if (isCharging) {
            if (unplugTs != 0L) sp.edit().clear().apply()
            unplugTs = 0L; unplugPct = -1
        } else if (curPct in 0..100) {
            val haveAnchor = unplugTs != 0L && unplugPct in 0..100
            val anchorRose = haveAnchor && curPct > unplugPct
            if (!haveAnchor || anchorRose) {
                // Fresh-install fallback OR pct went UP since the
                // recorded anchor (brief plug-in we missed while the
                // receiver was suspended / process wasn't restarted
                // yet). Mark anchor_source so a future caller can tell
                // this is a best-effort guess, not a disconnect event.
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

        return Snapshot(
            isCharging  = isCharging,
            curPct      = curPct,
            unplugTs    = unplugTs,
            unplugPct   = unplugPct,
            elapsedMs   = elapsedMs,
            consumedPct = consumed,
            ratePerMin  = rate,
            etaMs       = etaMs,
        )
    }

    fun fmtSinceLastCharge(s: Snapshot): String = when {
        s.isCharging -> "Charging — n/a"
        s.unplugTs <= 0L || s.unplugPct < 0 || s.curPct < 0 -> "Anchoring on next render…"
        else -> "${fmtDuration(s.elapsedMs)}  ·  −${s.consumedPct}%  (${s.unplugPct}% → ${s.curPct}%)"
    }

    fun fmtRate(s: Snapshot): String = when {
        s.isCharging -> "—"
        s.ratePerMin > 0.001 -> "%.3f%%/min".format(s.ratePerMin)
        else -> "Computing…"
    }

    fun fmtEta(s: Snapshot): String = when {
        s.isCharging -> "—"
        s.etaMs > 0L -> fmtDuration(s.etaMs)
        else -> "Computing…"
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
