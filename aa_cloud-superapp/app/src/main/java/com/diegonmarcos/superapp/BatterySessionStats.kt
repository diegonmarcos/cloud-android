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

        // ── DEBUG-VISIBILITY fields (Configs/About → Battery (debug)) ──
        // Where did the current discharge / charge anchor come from?
        // Values: "disconnect_event" (receiver fired), "connect_event"
        // (receiver fired), "first_read_fallback" (lazy mint because
        // receiver never fired before we read), "(none)" (no anchor).
        // Surfaces in the popup so the user can tell at a glance
        // whether the session is anchored on a real plug-event or on
        // the moment they opened the page.
        val unplugAnchorSource: String,
        val plugAnchorSource: String,
        // BatteryManager.BATTERY_PROPERTY_CURRENT_NOW raw value before
        // the mA→µA rescale. Same sign as the kernel reported. Lets
        // the debug surface explain why powerW is what it is.
        val currentRaw: Int,
        // True if the read() pass applied the |raw|<50_000 rescale
        // (Samsung mA → canonical µA). Surfaces so the user can see
        // their device's reporting convention.
        val rescaledMaToUa: Boolean,

        // ── BatteryManager system-service surface (AccuBattery's path) ──
        // Battery temperature in degrees Celsius, from sticky intent
        // EXTRA_TEMPERATURE (decidegrees → divide by 10). -1.0 when
        // unavailable. Works on every Android: BatteryStats samples
        // the kernel via system_server, not via sysfs, so even when
        // /sys/class/power_supply/battery/temp is SELinux-blocked
        // (Samsung One UI 7), this is populated.
        val batteryTempC: Double,
        // BATTERY_PROPERTY_CHARGE_COUNTER snapshot in µAh — kernel's
        // monotonic charge integrator. Used by [powerWSource=counter]
        // to derive smooth time-windowed power (AccuBattery's trick).
        // Long.MIN_VALUE when the OEM driver doesn't implement it.
        val chargeCounterUah: Long,
        // Which path produced `powerW`:
        //   "counter" — Δ(charge_counter) / Δt × voltage. Smooth.
        //               Immune to screen-off noise. PREFERRED.
        //   "instant" — V × current_now. Noisy when screen sleeps
        //               (Samsung drops to ~0.5 mA), used only as
        //               fallback when counter isn't available.
        //   "none"    — no valid reading on either path.
        val powerWSource: String,
        // ── Battery cycle counter (manual, AccuBattery-style) ──
        // We count cycles by summing the POSITIVE charge_counter
        // deltas (mAh accepted into the battery during charge) and
        // dividing by the empirical "full" capacity captured the
        // first time the kernel reads 100 %. So one full 0→100
        // charge = 1.0 cycle; partial charges add fractionally.
        //
        // -1.0 when uncalibrated (no 100 % reached since install).
        // Once calibrated, accurate to ±a few percent vs OEM gauge.
        val cycleCount: Double,
        // The kernel's CHARGE_COUNTER reading at the most recent
        // 100 % moment. Acts as our empirical "full" capacity for
        // the cycle-count calculation. 0 when uncalibrated.
        val peakChargeCounterUah: Long,
        // Total positive deltas summed since install — denominator-
        // free running total useful to surface raw "energy moved".
        val cumulativeChargedUah: Long,
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
        var unplugTs           = sp.getLong("unplug_ts", 0L)
        var unplugPct          = sp.getInt("unplug_pct", -1)
        var unplugAnchorSource = sp.getString("anchor_source", "") ?: ""
        var plugTs             = sp.getLong("plug_ts", 0L)
        var plugPct            = sp.getInt("plug_pct", -1)
        var plugAnchorSource   = sp.getString("anchor_source_plug", "") ?: ""

        if (isCharging) {
            // Stale discharge anchor — drop it so the next unplug starts
            // clean. PowerStateReceiver will mint a fresh one on the
            // ACTION_POWER_DISCONNECTED broadcast.
            if (unplugTs != 0L) {
                sp.edit().remove("unplug_ts").remove("unplug_pct").remove("anchor_source").apply()
                unplugTs = 0L; unplugPct = -1; unplugAnchorSource = ""
            }
            // Anchor the charge session — first read while plugged OR if
            // pct went DOWN since the recorded plug anchor (cable was
            // briefly unplugged while we were paused).
            //
            // IMMUTABILITY FIX: only the lazy fallback anchor gets
            // overwritten on the rose-above edge case. A receiver-set
            // anchor (connect_event) is authoritative — even if the
            // OEM's battery curve smooths upward by 1-2% on the next
            // read, we keep the original plug moment. The prior bug
            // was that any OEM with a smoothed gauge would reset the
            // anchor to first_read_fallback on every read, defeating
            // the receiver and matching the user's report
            // ("starting to calculate from the time I open first the
            // page").
            if (curPct in 0..100) {
                val haveAnchor = plugTs != 0L && plugPct in 0..100
                val anchorRose = haveAnchor && curPct < plugPct
                val isReceiverAnchor = plugAnchorSource == "connect_event"
                val shouldOverwrite = !haveAnchor || (anchorRose && !isReceiverAnchor)
                if (shouldOverwrite) {
                    sp.edit()
                        .putLong("plug_ts", now)
                        .putInt("plug_pct", curPct)
                        .putString("anchor_source_plug", "first_read_fallback")
                        .apply()
                    plugTs = now; plugPct = curPct; plugAnchorSource = "first_read_fallback"
                }
            }
        } else if (curPct in 0..100) {
            // Drop the plug anchor (we're no longer charging).
            if (plugTs != 0L) {
                sp.edit().remove("plug_ts").remove("plug_pct").remove("anchor_source_plug").apply()
                plugTs = 0L; plugPct = -1; plugAnchorSource = ""
            }
            // IMMUTABILITY FIX (mirror of charging branch): receiver-set
            // disconnect_event anchor is authoritative. Only lazy
            // first_read_fallback anchors get reset on the rose-above
            // edge case. This is what the user actually wanted —
            // anchor on the REAL cable-pull, not on the moment they
            // first open the battery page.
            val haveAnchor = unplugTs != 0L && unplugPct in 0..100
            val anchorRose = haveAnchor && curPct > unplugPct
            val isReceiverAnchor = unplugAnchorSource == "disconnect_event"
            val shouldOverwrite = !haveAnchor || (anchorRose && !isReceiverAnchor)
            if (shouldOverwrite) {
                sp.edit()
                    .putLong("unplug_ts", now)
                    .putInt("unplug_pct", curPct)
                    .putString("anchor_source", "first_read_fallback")
                    .apply()
                unplugTs = now; unplugPct = curPct; unplugAnchorSource = "first_read_fallback"
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
        val rescaledMaToUa = currentRaw != 0 && currentRaw != Int.MIN_VALUE &&
            kotlin.math.abs(currentRaw) < 50_000
        val currentUa = when {
            currentRaw == 0 || currentRaw == Int.MIN_VALUE -> 0
            // |raw| < 50_000 means the OEM is using mA (5 A in mA is
            // 5000, in µA it would be 5_000_000). Rescale to µA.
            rescaledMaToUa                                  -> currentRaw * 1000
            else                                           -> currentRaw
        }
        val powerWInstant = if (voltageMv > 0 && currentUa != 0)
            kotlin.math.abs((voltageMv.toLong() * currentUa.toLong()) / 1_000_000_000.0)
        else 0.0

        // ── BatteryManager system-service path (AccuBattery's trick) ──
        // CHARGE_COUNTER is the kernel's monotonic µAh integrator;
        // smooth time-windowed deltas give a much more accurate power
        // figure than V × instantaneous current (which on Samsung
        // drops to ~0.5 mA when the screen sleeps even at high real
        // load). Long.MIN_VALUE = OEM driver doesn't implement it.
        val bm2 = runCatching {
            ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        }.getOrNull()
        val chargeCounter = runCatching {
            bm2?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        }.getOrDefault(Long.MIN_VALUE)
        val chargeCounterAvailable = chargeCounter != Long.MIN_VALUE && chargeCounter > 0L

        val lastCounter   = sp.getLong("last_counter_uah", -1L)
        val lastCounterTs = sp.getLong("last_counter_ts", 0L)
        // Sample window: only trust the delta if the previous sample
        // is within 5s..10min — too fresh = noisy, too stale = the
        // kernel may have reset across a power event.
        val powerWFromCounter = if (
            chargeCounterAvailable && lastCounter > 0L &&
            lastCounterTs > 0L && (now - lastCounterTs) in 5_000L..600_000L &&
            voltageMv > 0
        ) {
            val deltaUah = chargeCounter - lastCounter
            val deltaMs  = now - lastCounterTs
            val deltaSec = deltaMs / 1000.0
            // µAh × 3600 / sec = µA → / 1_000_000 = A; × V = W.
            // abs() because discharging shows negative deltas.
            kotlin.math.abs(deltaUah.toDouble() * 3600.0 / deltaSec / 1_000_000.0) *
                (voltageMv / 1000.0)
        } else 0.0
        // Persist the new counter sample for next call's delta.
        if (chargeCounterAvailable) {
            sp.edit()
                .putLong("last_counter_uah", chargeCounter)
                .putLong("last_counter_ts", now)
                .apply()
        }
        // Prefer counter path when available; instant is only used
        // as a fallback to keep the row populated on devices that
        // refuse CHARGE_COUNTER (Long.MIN_VALUE).
        val (powerW, powerWSource) = when {
            powerWFromCounter > 0.005 -> powerWFromCounter to "counter"
            powerWInstant     > 0.005 -> powerWInstant     to "instant"
            else                      -> 0.0               to "none"
        }

        // ── Cycle counting (AccuBattery-style) ──
        // Sum positive deltas (mAh accepted while charging) since
        // install; divide by the empirical "full" CHARGE_COUNTER
        // captured the first time we read 100 % capacity. Until
        // calibrated, cycleCount = -1.0 → UI shows
        // "calibrating — charge to 100 %".
        var cumulativeChargedUah = sp.getLong("cumulative_charged_uah", 0L)
        if (chargeCounterAvailable && lastCounter > 0L && isCharging) {
            val delta = chargeCounter - lastCounter
            // Sanity-filter: a single sample should never accept
            // more than ~10 % of a 5000 mAh cell (500 mAh = 500_000
            // µAh). Anything larger is a counter reset, not real
            // charging — drop it.
            if (delta in 1L..500_000_000L) {
                cumulativeChargedUah += delta
                sp.edit().putLong("cumulative_charged_uah", cumulativeChargedUah).apply()
            }
        }
        var peakChargeCounter = sp.getLong("peak_at_full_uah", 0L)
        if (chargeCounterAvailable && curPct >= 100 && chargeCounter > peakChargeCounter) {
            peakChargeCounter = chargeCounter
            sp.edit().putLong("peak_at_full_uah", peakChargeCounter).apply()
        }
        val cycleCount = if (peakChargeCounter > 0L)
            cumulativeChargedUah.toDouble() / peakChargeCounter.toDouble()
        else -1.0

        // ── Battery temperature ──
        // Sticky intent EXTRA_TEMPERATURE in decidegrees Celsius
        // (so divide by 10). Works on every Android — the broadcast
        // is populated by system_server regardless of SELinux sysfs
        // restrictions. -1 sentinel → -1.0 here.
        val tempDc = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val batteryTempC = if (tempDc != Int.MIN_VALUE) tempDc / 10.0 else -1.0

        // ── Anchor backstop via polling-detected transition ──
        // When the receiver missed the broadcast (Samsung Sleeping
        // Apps, Doze, or any aggressive OEM background killer), we
        // can still detect the plug/unplug by comparing the CURRENT
        // isCharging to the LAST seen state. If it flipped AND the
        // anchor for that direction is empty / lazy, mint a
        // "transition_detected" anchor — accurate to within the
        // poll interval, not perfect but vastly better than
        // first_read_fallback.
        val lastWasCharging = sp.getBoolean("last_was_charging", isCharging)
        if (lastWasCharging && !isCharging && curPct in 0..100 &&
            (unplugAnchorSource.isEmpty() || unplugAnchorSource == "first_read_fallback")) {
            sp.edit()
                .putLong("unplug_ts", now)
                .putInt("unplug_pct", curPct)
                .putString("anchor_source", "transition_detected")
                .apply()
            unplugTs = now; unplugPct = curPct
            unplugAnchorSource = "transition_detected"
        } else if (!lastWasCharging && isCharging && curPct in 0..100 &&
            (plugAnchorSource.isEmpty() || plugAnchorSource == "first_read_fallback")) {
            sp.edit()
                .putLong("plug_ts", now)
                .putInt("plug_pct", curPct)
                .putString("anchor_source_plug", "transition_detected")
                .apply()
            plugTs = now; plugPct = curPct
            plugAnchorSource = "transition_detected"
        }
        sp.edit().putBoolean("last_was_charging", isCharging).apply()

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
            unplugAnchorSource = unplugAnchorSource.ifEmpty { "(none)" },
            plugAnchorSource   = plugAnchorSource.ifEmpty { "(none)" },
            currentRaw         = currentRaw,
            rescaledMaToUa     = rescaledMaToUa,
            batteryTempC       = batteryTempC,
            chargeCounterUah   = chargeCounter,
            powerWSource       = powerWSource,
            cycleCount         = cycleCount,
            peakChargeCounterUah = peakChargeCounter,
            cumulativeChargedUah = cumulativeChargedUah,
        )
    }

    /** Wipe the current session's anchor + persisted state. Lets the
     *  user re-mint on the next plug/unplug cycle from a clean slate.
     *  Surfaced from the debug action button in Configs/About →
     *  Battery (debug). Equivalent to "I just installed the APK" —
     *  next read will lazy-mint a first_read_fallback anchor, then
     *  whenever the user next plugs/unplugs the receiver overwrites
     *  with the authoritative event-anchor. */
    fun resetAnchor(ctx: Context) {
        ctx.getSharedPreferences("battery_session", Context.MODE_PRIVATE)
            .edit()
            .remove("unplug_ts").remove("unplug_pct").remove("anchor_source")
            .remove("plug_ts").remove("plug_pct").remove("anchor_source_plug")
            .apply()
    }

    /** Format the charger spec row. Prefers the LIVE sysfs reading
     *  (`/sys/class/power_supply/usb/...` etc., no perm needed);
     *  falls back to the negotiated MAX via dumpsys (needs DUMP);
     *  falls back to "—" when nothing readable. Shape:
     *  "14.7 W (USB) live" / "25.5 W (USB) max" / "—".
     *
     *  KDoc gotcha: kept `usb/...` instead of `usb/&#42;` — Kotlin's
     *  block comments are nested-aware, so a literal `/&#42;` here
     *  would open a nested block-comment and swallow the rest of
     *  the file (the trap that broke CI twice; see also SysfsProc.kt). */
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

    /** Battery temperature row — "39.4 °C" or "—" when unavailable. */
    fun fmtBatteryTemp(s: Snapshot): String =
        if (s.batteryTempC < 0) "—" else "%.1f °C".format(s.batteryTempC)

    /** Cycle-count row — "12.5 cycles" or "calibrating (charge to 100 %)"
     *  until peak_at_full_uah is captured. */
    fun fmtCycleCount(s: Snapshot): String = when {
        s.cycleCount < 0      -> "calibrating — charge to 100 %"
        s.peakChargeCounterUah == 0L -> "calibrating — charge to 100 %"
        else                  -> "%.2f cycles  (≈ %d mAh full)".format(
            s.cycleCount, s.peakChargeCounterUah / 1000L)
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
