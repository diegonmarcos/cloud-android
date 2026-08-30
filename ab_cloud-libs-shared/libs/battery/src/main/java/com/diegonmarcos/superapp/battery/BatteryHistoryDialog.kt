package com.diegonmarcos.superapp.battery

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full battery HISTORY page — opened from Configs/About → Battery &
 * Usage, the same way "Firewall Details" opens [FirewallDialog] from the
 * Firewall section. Sibling of [EnergyUsageDialog]: that one answers
 * "what is draining me RIGHT NOW", this one answers "what has this
 * battery been doing over time".
 *
 * Everything rendered here comes from [BatteryHistoryStore], which
 * [BatterySessionStats.read] fills in at every charge/discharge flip,
 * plus the live cycle/cumulative counters carried on the current
 * snapshot. Four cards, one scroll:
 *   1. Lifetime — cycle count, energy moved, calibration state.
 *   2. Averages — mean discharge and charge rate across all stored runs.
 *   3. Daily — per-calendar-day drained/charged percent with a bar.
 *   4. Sessions — every stored run, newest first.
 *
 * Full-screen DialogFragment, programmatic dark views (no resource deps,
 * so it can live lib-side) — open with `BatteryHistoryDialog().show(fm, TAG)`.
 */
class BatteryHistoryDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(0xFF0A0A0F.toInt()))
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater, c: ViewGroup?, s: Bundle?,
    ): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            isFillViewport = true
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, dp(ctx, 24), p, dp(ctx, 48))
        }
        scroll.addView(root)
        build(ctx, root)
        return scroll
    }

    /** Tear the content down and rebuild in place — used after "Clear history". */
    private fun rebuild() {
        val scroll = view as? ScrollView ?: return
        val root = scroll.getChildAt(0) as? LinearLayout ?: return
        root.removeAllViews()
        build(scroll.context, root)
    }

    private fun build(ctx: Context, root: LinearLayout) {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "Battery History"
            setTextColor(Color.WHITE); textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "✕"; setTextColor(0xCCFFFFFF.toInt()); textSize = 20f
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            isClickable = true; setOnClickListener { dismiss() }
        })
        root.addView(header)

        val sessions = runCatching { BatteryHistoryStore.sessions(ctx) }.getOrDefault(emptyList())
        val days = runCatching { BatteryHistoryStore.days(ctx) }.getOrDefault(emptyList())
        val snap = runCatching { BatterySessionStats.read(ctx) }.getOrNull()

        // 1) Lifetime counters — the kernel-integrator figures the live
        //    snapshot already maintains, restated as history.
        card(ctx, root, "Lifetime") { box ->
            if (snap == null) { box.addView(small(ctx, "No battery reading available.")); return@card }
            big(ctx, box,
                if (snap.cycleCount >= 0) "%.2f cycles".format(snap.cycleCount) else "calibrating",
                BatterySessionStats.fmtCycleCount(snap))
            box.addView(line(ctx, "Energy moved",
                if (snap.cumulativeChargedUah > 0) "${snap.cumulativeChargedUah / 1000L} mAh accepted"
                else "—"))
            box.addView(line(ctx, "Full capacity seen",
                if (snap.peakChargeCounterUah > 0) "${snap.peakChargeCounterUah / 1000L} mAh" else "—"))
            box.addView(line(ctx, "Sessions recorded", sessions.size.toString()))
            box.addView(line(ctx, "Days recorded", days.size.toString()))
            box.addView(small(ctx, "Cycles are counted AccuBattery-style: positive charge-counter deltas summed since install, divided by the charge-counter reading captured at 100 %."))
        }

        // 2) Averages across every stored run.
        card(ctx, root, "Average rates  ·  all recorded sessions") { box ->
            if (sessions.isEmpty()) {
                box.addView(small(ctx, "Nothing recorded yet — a run is archived the moment you plug in or unplug. Come back after a charge cycle."))
                return@card
            }
            val drain = BatteryHistoryStore.averageRatePerHour(sessions, charging = false)
            val gain = BatteryHistoryStore.averageRatePerHour(sessions, charging = true)
            big(ctx, box,
                if (drain > 0) "−%.2f %%/h".format(drain) else "—",
                "mean discharge rate over ${sessions.count { !it.charging }} discharge runs")
            box.addView(line(ctx, "Mean charge rate",
                if (gain > 0) "+%.2f %%/h".format(gain) else "—"))
            box.addView(line(ctx, "Discharge runs", sessions.count { !it.charging }.toString()))
            box.addView(line(ctx, "Charge runs", sessions.count { it.charging }.toString()))
            val onBattery = sessions.filter { !it.charging }.sumOf { it.durationMs }
            val onCharger = sessions.filter { it.charging }.sumOf { it.durationMs }
            box.addView(line(ctx, "Total on battery", fmtDur(onBattery)))
            box.addView(line(ctx, "Total on charger", fmtDur(onCharger)))
        }

        // 3) Per-day aggregates with a proportional bar so the heavy days
        //    are visible at a glance without a charting dependency.
        card(ctx, root, "By day  ·  last ${BatteryHistoryStore.MAX_DAYS} days kept") { box ->
            if (days.isEmpty()) {
                box.addView(small(ctx, "No completed day yet.")); return@card
            }
            val peak = days.maxOf { maxOf(it.drainedPct, it.chargedPct) }.coerceAtLeast(1)
            days.forEach { d ->
                box.addView(line(ctx, d.date,
                    "−${d.drainedPct}%  +${d.chargedPct}%  ·  ${d.sessions} runs"))
                box.addView(bar(ctx, d.drainedPct, peak, 0xFFF56565.toInt()))
                box.addView(bar(ctx, d.chargedPct, peak, 0xFF48BB78.toInt()))
                box.addView(small(ctx,
                    "on battery ${fmtDur(d.dischargeMs)}  ·  charging ${fmtDur(d.chargeMs)}"))
            }
            box.addView(small(ctx, "A run counts entirely on the day it ENDED — runs that straddle midnight aren't split."))
        }

        // 4) The raw ledger, newest first.
        card(ctx, root, "Sessions  ·  newest first") { box ->
            if (sessions.isEmpty()) { box.addView(small(ctx, "Empty.")); return@card }
            sessions.forEach { s ->
                val sign = if (s.charging) "+" else "−"
                box.addView(line(ctx,
                    "${fmtStamp(s.startTs)} → ${fmtClock(s.endTs)}",
                    "$sign${kotlin.math.abs(s.deltaPct)}%  ${s.startPct}→${s.endPct}"))
                box.addView(small(ctx,
                    "${if (s.charging) "charge" else "discharge"}  ·  ${fmtDur(s.durationMs)}  ·  " +
                        if (s.ratePerHour > 0) "%.2f %%/h".format(s.ratePerHour) else "rate n/a"))
            }
            box.addView(small(ctx, "Ledger is capped at ${BatteryHistoryStore.MAX_SESSIONS} runs; the oldest are dropped."))
            box.addView(pill(ctx, "Clear history") {
                BatteryHistoryStore.clear(ctx)
                rebuild()
            })
        }
    }

    // ── tiny view helpers (same idiom as EnergyUsageDialog) ─────────────
    private fun card(ctx: Context, parent: LinearLayout, title: String, body: (LinearLayout) -> Unit) {
        parent.addView(TextView(ctx).apply {
            text = title; setTextColor(0xFFB794F4.toInt()); textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(ctx, 18), 0, dp(ctx, 6))
        })
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat(); setColor(0xFF15151E.toInt())
            }
            val p = dp(ctx, 12); setPadding(p, p, p, p)
        }
        body(box); parent.addView(box)
    }

    private fun big(ctx: Context, box: LinearLayout, value: String, sub: String) {
        box.addView(TextView(ctx).apply {
            text = value; setTextColor(Color.WHITE); textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        box.addView(TextView(ctx).apply {
            text = sub; setTextColor(0x99FFFFFF.toInt()); textSize = 12f
            setPadding(0, 0, 0, dp(ctx, 6))
        })
    }

    private fun line(ctx: Context, k: String, v: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        addView(TextView(ctx).apply {
            text = k; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(ctx).apply {
            text = v; setTextColor(Color.WHITE); textSize = 13f
            typeface = Typeface.MONOSPACE; gravity = Gravity.END
        })
    }

    /** Proportional horizontal bar — a plain coloured View whose width is
     *  a layout weight, so no drawing code and no charting dependency. */
    private fun bar(ctx: Context, value: Int, peak: Int, color: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)
        ).apply { topMargin = dp(ctx, 2) }
        val filled = value.coerceIn(0, peak).toFloat()
        addView(View(ctx).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, filled)
        })
        addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, (peak - filled).coerceAtLeast(0.0001f))
        })
    }

    private fun pill(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 20).toFloat(); setColor(0xFF3B2A63.toInt())
        }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun small(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; setTextColor(0x77FFFFFF.toInt()); textSize = 11f
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 4))
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun fmtDur(ms: Long): String {
        val m = ms / 60_000L
        return when {
            m >= 60 -> "${m / 60}h ${m % 60}m"
            m >= 1 -> "${m}m"
            else -> "${ms / 1000L}s"
        }
    }

    private fun fmtStamp(ts: Long): String =
        SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(ts))

    private fun fmtClock(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    companion object { const val TAG = "battery_history_dialog" }
}
