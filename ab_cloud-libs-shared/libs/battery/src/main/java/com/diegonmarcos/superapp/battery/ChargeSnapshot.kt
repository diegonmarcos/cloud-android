package com.diegonmarcos.superapp.battery

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-tap charging snapshot — capture the charge/PD-negotiation fields at a
 * moment in time (ideally at <30% battery, cool) so the decisive fast-charge
 * evidence can be grabbed the instant it happens, then read back later.
 *
 * Captures the always-available native fields (level / current / voltage /
 * temp / power) plus, when the embedded-adb channel is connected, the
 * system-service truth from `dumpsys battery`: Max charging current/voltage,
 * Charging state, IC-auth, and the raw last ACTION_BATTERY_CHANGED line
 * (which carries charge_type / charger_type / hvc / mcc / mcv).
 *
 * Persists a capped ring (last [CAP]) in SharedPreferences as a JSON array,
 * so snapshots survive app restarts and can be compared across SOC levels.
 */
object ChargeSnapshot {

    private const val PREFS = "charge_snapshots"
    private const val KEY = "snaps"
    private const val CAP = 50

    /** Capture one snapshot, persist it, and return it as JSON. */
    fun capture(ctx: Context): JSONObject {
        val s = BatterySessionStats.read(ctx)
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("level", s.curPct)
        o.put("charging", s.isCharging)
        o.put("current_ma", if (s.currentUa != 0) s.currentUa / 1000 else 0)
        o.put("voltage_mv", s.voltageMv)
        o.put("temp_c", s.batteryTempC)
        o.put("power_w", Math.round(s.powerW * 10.0) / 10.0)

        val dump = runCatching { EmbeddedAdbChannel.exec(ctx, "dumpsys battery") }.getOrNull()
        if (!dump.isNullOrBlank() && !dump.startsWith("ERR") && dump.contains("Battery")) {
            o.put("adb", true)
            o.put("max_charging_current", grab(dump, "Max charging current:"))
            o.put("max_charging_voltage", grab(dump, "Max charging voltage:"))
            o.put("charging_state", grab(dump, "Charging state:"))
            o.put("charging_policy", grab(dump, "Charging policy:"))
            o.put("ic_auth", grab(dump, "IcAuthenticationResults:"))
            o.put("last_event", lastEvent(dump))
        } else {
            o.put("adb", false)
        }
        persist(ctx, o)
        return o
    }

    /** Newest-first list of stored snapshots. */
    fun recent(ctx: Context): JSONArray =
        runCatching {
            JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        }.getOrDefault(JSONArray())

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun persist(ctx: Context, snap: JSONObject) {
        val arr = recent(ctx)
        val out = JSONArray()
        out.put(snap)                                  // newest first
        for (i in 0 until minOf(arr.length(), CAP - 1)) out.put(arr.get(i))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, out.toString()).apply()
    }

    /** First line containing [label] → the trimmed text after it. */
    private fun grab(text: String, label: String): String {
        for (line in text.lineSequence()) {
            val i = line.indexOf(label)
            if (i >= 0) return line.substring(i + label.length).trim()
        }
        return "—"
    }

    /** Raw last ACTION_BATTERY_CHANGED line (richest single line). */
    private fun lastEvent(text: String): String {
        var last = "—"
        for (line in text.lineSequence()) {
            if (line.contains("ACTION_BATTERY_CHANGED")) last = line.substringAfter("Sending ").trim()
        }
        return last
    }

    /** Compact one-line display for the UI list. */
    fun fmtLine(o: JSONObject): String {
        val mcv = o.optString("max_charging_voltage", "")
        val cs = o.optString("charging_state", "")
        val tag = when {
            !o.optBoolean("adb", false) -> "no-adb"
            mcv != "0" && mcv != "—" && mcv.isNotEmpty() -> "PD ${mcv}"
            else -> "5V/normal"
        }
        return "%d%%  %dmA  %.1fW  %.0f°C  [%s]".format(
            o.optInt("level", -1),
            o.optInt("current_ma", 0),
            o.optDouble("power_w", 0.0),
            o.optDouble("temp_c", 0.0),
            tag,
        )
    }
}
