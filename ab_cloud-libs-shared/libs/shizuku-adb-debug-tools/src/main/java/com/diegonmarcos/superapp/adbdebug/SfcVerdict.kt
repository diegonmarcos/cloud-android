package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Samsung Super Fast Charging "why isn't it fast" verdict.
 *
 * The decisive negotiation evidence on a stock (non-rooted) Samsung lives in
 * `dumpsys battery` — the only charge surface readable from shell uid once
 * One UI hardens `/sys/class/power_supply/`* (the tcpm/PD kernel log is
 * `dmesg_restrict`-blocked and `dumpsys usb` is stripped). This reads the two
 * data-driven `samsung-sfc` bundle commands (model + dumpsys battery) and
 * turns them into a one-line verdict so you don't eyeball raw dumps.
 *
 * SPLIT (so the logic is testable with zero Android/device deps):
 *   • [evaluate] — PURE. (model, dumpsysText, deviceCaps, thresholds) -> [Verdict].
 *                  No Android imports, no org.json on the INPUT path — only
 *                  stdlib string/regex parsing. This is what the JVM unit test
 *                  drives with fixtures simulating every protocol case.
 *   • [Verdict.toJson] — manual string build (no org.json), also pure.
 *   • [run] — the Android wrapper: executes the data-driven bundle through the
 *             [ShellChannels] ladder and feeds [evaluate] the live output.
 *
 * It does NOT (cannot) fake hardware watts — a full battery never negotiates
 * fast charge regardless of cable/charger. It reports what the negotiation IS
 * doing and WHY a faster tier isn't engaging.
 */
object SfcVerdict {

    /** build.json::shizuku_diagnostics.bundles[] id (single source of the cmds). */
    private const val BUNDLE_ID = "samsung-sfc"

    /** Negotiated charge tier, inferred from the live charge surface. */
    enum class Tier { NOT_CHARGING, FULL_OR_TAPERING, FAST_HV, SLOW_5V, UNKNOWN }

    data class Verdict(
        val model: String,
        val deviceMaxWatts: Int?,        // null = unknown model (not in device_caps)
        val powered: Boolean,
        val level: Int,
        val statusFull: Boolean,
        val sfcSettingOn: Boolean,
        val afcSettingOn: Boolean,
        val maxChargeVoltageMv: Int,     // from "Max charging voltage" (µV→mV) or mcv
        val maxChargeCurrentMa: Int,     // from "Max charging current" (µA→mA) or mcc
        val highVoltageEngaged: Boolean,
        val savedMaxCurrentMa: Int,      // mSavedBatteryMaxCurrent — peak ever seen
        val tier: Tier,
        val cableSuspect: Boolean,       // honest: only when slow DESPITE being a fair test
        val verdict: String,
        val reasons: List<String>,
        val channel: String = "",
    ) {
        fun toJson(): String {
            val sb = StringBuilder("{")
            sb.append("\"ok\":true,")
            sb.append("\"model\":\"").append(esc(model)).append("\",")
            sb.append("\"device_max_watts\":").append(deviceMaxWatts?.toString() ?: "null").append(',')
            sb.append("\"powered\":").append(powered).append(',')
            sb.append("\"level\":").append(level).append(',')
            sb.append("\"status_full\":").append(statusFull).append(',')
            sb.append("\"sfc_setting_on\":").append(sfcSettingOn).append(',')
            sb.append("\"afc_setting_on\":").append(afcSettingOn).append(',')
            sb.append("\"max_charge_voltage_mv\":").append(maxChargeVoltageMv).append(',')
            sb.append("\"max_charge_current_ma\":").append(maxChargeCurrentMa).append(',')
            sb.append("\"high_voltage_engaged\":").append(highVoltageEngaged).append(',')
            sb.append("\"saved_max_current_ma\":").append(savedMaxCurrentMa).append(',')
            sb.append("\"tier\":\"").append(tier.name).append("\",")
            sb.append("\"cable_suspect\":").append(cableSuspect).append(',')
            sb.append("\"channel\":\"").append(esc(channel)).append("\",")
            sb.append("\"verdict\":\"").append(esc(verdict)).append("\",")
            sb.append("\"reasons\":[")
            reasons.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.append('"').append(esc(r)).append('"')
            }
            sb.append("]}")
            return sb.toString()
        }

        private fun esc(s: String) =
            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim()
    }

    // ───────────────────────── PURE CORE (unit-tested) ─────────────────────────

    /**
     * Turn a raw `dumpsys battery` dump + the `ro.product.model` string into a
     * [Verdict], using the data-driven [deviceCaps] (model-prefix → max watts)
     * and thresholds. Pure: stdlib only, safe to call off-device in tests.
     */
    fun evaluate(
        model: String,
        dumpsys: String,
        deviceCaps: Map<String, Int>,
        highVoltageMvThreshold: Int,
        fullLevelPct: Int,
    ): Verdict {
        val powered = boolField(dumpsys, "USB powered") || boolField(dumpsys, "AC powered") ||
            boolField(dumpsys, "Wireless powered") || boolField(dumpsys, "Dock powered")
        val level = intField(dumpsys, "level") ?: -1
        val status = intField(dumpsys, "status") ?: 0          // 5 = FULL (BatteryManager)
        val sfcOn = boolField(dumpsys, "Super Fast Charging Settings")
        val afcOn = boolField(dumpsys, "Adaptive Fast Charging Settings")
        // AOSP reports these in µA / µV; Samsung leaves them 0 unless a contract is live.
        val maxIuA = intField(dumpsys, "Max charging current") ?: 0
        val maxVuV = intField(dumpsys, "Max charging voltage") ?: 0
        val savedMax = intField(dumpsys, "mSavedBatteryMaxCurrent") ?: 0

        // Richest single line: the last ACTION_BATTERY_CHANGED carries mcv/mcc/hvc.
        val lastEvent = dumpsys.lineSequence().lastOrNull { it.contains("ACTION_BATTERY_CHANGED") } ?: ""
        val mcvMv = kvInt(lastEvent, "mcv") ?: 0               // max charging voltage (mV)
        val mccMa = kvInt(lastEvent, "mcc") ?: 0               // max charging current (mA)
        val hvc = kvBool(lastEvent, "hvc")

        val maxVoltageMv = maxOf(mcvMv, maxVuV / 1000)
        val maxCurrentMa = maxOf(mccMa, maxIuA / 1000)
        val statusFull = status == 5
        val highV = maxVoltageMv >= highVoltageMvThreshold || (hvc && maxVoltageMv > 0)

        val deviceMaxW = longestPrefixCap(model, deviceCaps)

        val tier = when {
            !powered -> Tier.NOT_CHARGING
            statusFull || (level in 0..100 && level >= fullLevelPct) -> Tier.FULL_OR_TAPERING
            highV -> Tier.FAST_HV
            else -> Tier.SLOW_5V
        }

        val reasons = ArrayList<String>()
        if (deviceMaxW != null)
            reasons.add("$model caps at ${deviceMaxW}W wired — a higher-rated cable/charger cannot exceed this.")
        if (!sfcOn)
            reasons.add("Super Fast Charging is OFF in Settings → Battery → Charging. Enable it.")
        when (tier) {
            Tier.NOT_CHARGING ->
                reasons.add("Not on external power — nothing to negotiate.")
            Tier.FULL_OR_TAPERING ->
                reasons.add("Battery is ${if (statusFull) "FULL" else "$level% (≥$fullLevelPct%)"} — fast charge tapers near full by design; test below ~50% to see it engage.")
            Tier.FAST_HV ->
                reasons.add("High-voltage contract engaged (${maxVoltageMv}mV) — fast charging IS negotiating.")
            Tier.SLOW_5V ->
                reasons.add("Charging at 5V only — no high-voltage contract. Suspect the CHARGER's PPS/AFC capability (not the cable) or a thermal cap.")
            Tier.UNKNOWN -> {}
        }
        if (savedMax > 0)
            reasons.add("Peak current ever recorded: ${savedMax}mA (~${"%.0f".format(savedMax * 4.3 / 1000)}W) — the chain HAS fast-charged before, so the hardware path works.")

        // Honest cable-suspect flag: only when it's a fair test (powered, not full,
        // setting on) yet still stuck at 5V. Even then the charger is the likelier culprit.
        val cableSuspect = tier == Tier.SLOW_5V && sfcOn

        val verdict = when (tier) {
            Tier.NOT_CHARGING -> "Not charging — plug in to evaluate."
            Tier.FULL_OR_TAPERING ->
                "Healthy — battery full/high so it's tapering, not denied." +
                    (deviceMaxW?.let { " Device ceiling: ${it}W." } ?: "")
            Tier.FAST_HV ->
                "Fast charging engaged (~${maxVoltageMv}mV" +
                    (if (maxCurrentMa > 0) "/${maxCurrentMa}mA" else "") + ")." +
                    (deviceMaxW?.let { " Device ceiling: ${it}W." } ?: "")
            Tier.SLOW_5V ->
                "Stuck at 5V" + (if (sfcOn) " despite SFC enabled — charger PPS/cable/thermal suspect." else " — enable Super Fast Charging.")
            Tier.UNKNOWN -> "Indeterminate — dumpsys battery unparseable."
        }

        return Verdict(
            model = model, deviceMaxWatts = deviceMaxW, powered = powered, level = level,
            statusFull = statusFull, sfcSettingOn = sfcOn, afcSettingOn = afcOn,
            maxChargeVoltageMv = maxVoltageMv, maxChargeCurrentMa = maxCurrentMa,
            highVoltageEngaged = highV, savedMaxCurrentMa = savedMax, tier = tier,
            cableSuspect = cableSuspect, verdict = verdict, reasons = reasons,
        )
    }

    /** "Label: <int>" → int (first match), tolerant of µA suffixes / whitespace. */
    internal fun intField(text: String, label: String): Int? {
        val re = Regex("""(?m)^\s*${Regex.escape(label)}\s*:\s*(-?\d+)""")
        return re.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** "Label: true/false" → Bool (first match). */
    internal fun boolField(text: String, label: String): Boolean {
        val re = Regex("""(?m)^\s*${Regex.escape(label)}\s*:\s*(true|false)""")
        return re.find(text)?.groupValues?.get(1) == "true"
    }

    /** "key:123" inside a CSV-ish line → int. */
    internal fun kvInt(line: String, key: String): Int? =
        Regex("""\b${Regex.escape(key)}:(-?\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** "key:true" inside a CSV-ish line → bool. */
    internal fun kvBool(line: String, key: String): Boolean =
        Regex("""\b${Regex.escape(key)}:(true|false)""").find(line)?.groupValues?.get(1) == "true"

    /** Longest model-prefix entry in the cap map that the model starts with. */
    internal fun longestPrefixCap(model: String, caps: Map<String, Int>): Int? =
        caps.entries.filter { model.startsWith(it.key) }.maxByOrNull { it.key.length }?.value

    // ───────────────────────── ANDROID WRAPPER ─────────────────────────

    /**
     * Run the data-driven `samsung-sfc` bundle through the shell-channel ladder
     * and return the [Verdict] as JSON. Commands are defined ONCE in build.json
     * (not duplicated here). On no channel: {"ok":false,...}.
     */
    fun run(ctx: Context): String {
        val raw = AdbDiagnostics.runBundle(ctx, BUNDLE_ID)
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return """{"ok":false,"verdict":"diagnostics returned unparseable output"}"""
        if (!root.optBoolean("ok", false)) {
            return """{"ok":false,"verdict":"no shell channel ready (${root.optString("channel", "none")}) — pair embedded-adb or start the local server"}"""
        }
        val results = root.optJSONArray("results") ?: JSONArray()
        var model = ""
        var dump = ""
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            when (r.optString("id")) {
                "model" -> model = r.optString("out").trim()
                "dumpsys_battery" -> dump = r.optString("out")
            }
        }
        val cfg = decodeConfig()
        return evaluate(model, dump, cfg.deviceCaps, cfg.hvThresholdMv, cfg.fullLevelPct)
            .copy(channel = root.optString("channel", ""))
            .toJson()
    }

    private data class Config(
        val deviceCaps: Map<String, Int>,
        val hvThresholdMv: Int,
        val fullLevelPct: Int,
    )

    /** Decode BuildConfig.SFC_CONFIG_B64 (the build.json samsung_sfc block). */
    private fun decodeConfig(): Config = runCatching {
        val json = String(Base64.decode(BuildConfig.SFC_CONFIG_B64, Base64.DEFAULT))
        val o = JSONObject(json)
        val capsObj = o.optJSONObject("device_caps") ?: JSONObject()
        val caps = HashMap<String, Int>()
        for (k in capsObj.keys()) caps[k] = capsObj.optInt(k)
        Config(
            deviceCaps = caps,
            hvThresholdMv = o.optInt("high_voltage_mv_threshold", 9000),
            fullLevelPct = o.optInt("full_level_pct", 95),
        )
    }.getOrDefault(Config(emptyMap(), 9000, 95))
}
