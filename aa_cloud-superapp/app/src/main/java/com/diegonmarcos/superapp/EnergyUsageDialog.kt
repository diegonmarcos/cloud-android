package com.diegonmarcos.superapp

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * AccuBattery-style energy page — opened from Configs/About → Battery &
 * Usage. Renders, in one scroll:
 *   1. Live discharge speed (whole-device mA / %·h from
 *      BatterySessionStats — the same snapshot the status strip uses).
 *   2. "Draining the device" — marginal mA per system state from the
 *      Tier-1 watchdog ([EnergyWatchdog.attribution]).
 *   3. "By foreground app" — per-app average draw + energy share while
 *      that app was foreground (correlation, the no-privilege proxy for
 *      AccuBattery's per-app list; the Shizuku tier upgrades this to
 *      exact mAh).
 *   4. "Inside Cloud SuperApp" — our own subsystem ledger
 *      ([EnergyLedger]) so we can see + fix what WE cost.
 *
 * Full-screen DialogFragment so it's decoupled from the host's fragment
 * container — open with `EnergyUsageDialog().show(fm, TAG)`.
 */
class EnergyUsageDialog : DialogFragment() {

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

    private fun build(ctx: Context, root: LinearLayout) {
        // Header + close
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "Battery Usage"
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

        // 1) Live discharge speed
        val bs = runCatching { BatterySessionStats.read(ctx) }.getOrNull()
        card(ctx, root, "Discharging speed") { box ->
            if (bs == null) { box.addView(line(ctx, "—", "no data")); return@card }
            val ma = if (bs.currentUa != 0) (kotlin.math.abs(bs.currentUa) / 1000) else 0
            big(ctx, box,
                if (ma > 0) "$ma mA" else "—",
                BatterySessionStats.fmtRateUnified(bs))
            box.addView(line(ctx, "Live draw", BatterySessionStats.fmtPowerRow(bs)))
            box.addView(line(ctx, "Battery temp", BatterySessionStats.fmtBatteryTemp(bs)))
            box.addView(line(ctx, bs.let { if (it.isCharging) "Time to full" else "Est. battery last" },
                BatterySessionStats.fmtEtaDuration(bs)))
        }

        val attr = runCatching { EnergyWatchdog.attribution(ctx) }.getOrDefault(emptyMap())
        val sampleCount = (attr["samples"] as? Int) ?: 0

        // 2) Device states → marginal draw
        @Suppress("UNCHECKED_CAST")
        val states = attr["states"] as? Map<String, Map<String, Any>>
        card(ctx, root, "Draining the device  ·  $sampleCount samples") { box ->
            if (states == null || sampleCount == 0) {
                box.addView(small(ctx, "Collecting samples… open the app a few times while on battery, then reopen this page.")); return@card
            }
            val baseline = (attr["baseline_idle_ma"] as? Int) ?: 0
            box.addView(line(ctx, "Idle baseline", "$baseline mA"))
            states.entries.sortedByDescending { (it.value["marginal_ma"] as? Int) ?: 0 }.forEach { (k, v) ->
                val m = (v["marginal_ma"] as? Int) ?: 0
                val n = (v["samples"] as? Int) ?: 0
                if (n > 0) box.addView(line(ctx, k.replace('_', ' '),
                    (if (m >= 0) "+$m mA" else "$m mA") + "   ($n)"))
            }
        }

        // 3) By app — AccuBattery-style estimate (foreground time × avg
        //    screen-on discharge mA). No Shizuku / privilege; just Usage
        //    Access. Same method AccuBattery uses for its per-app list.
        card(ctx, root, "By app  ·  estimate (foreground × draw)") { box ->
            if (!EnergyWatchdog.hasUsageAccess(ctx)) {
                box.addView(small(ctx, "Needs Usage Access. Grant it in Configs/About → Permissions → Grant Usage Access."))
                return@card
            }
            val est = runCatching { EnergyWatchdog.perAppEstimate(ctx) }.getOrDefault(emptyList())
            if (est.isEmpty()) {
                box.addView(small(ctx, "No foreground time in the current discharge session yet.")); return@card
            }
            box.addView(small(ctx, "mAh ≈ app foreground-time × avg screen-on draw (this discharge session)"))
            est.take(15).forEach { e ->
                box.addView(appRow(ctx, e.pkg, e.label,
                    "%.1f mAh  ·  %s".format(e.mAh, fmtDur(e.fgMs))))
            }
        }

        // 3b) Exact per-app mAh via Shizuku (ground truth, if available)
        card(ctx, root, "Per-app exact  ·  Shizuku") { box ->
            box.addView(small(ctx, ShizukuEnergy.status()))
            when {
                !ShizukuEnergy.isAvailable() ->
                    box.addView(small(ctx, "Start Shizuku to unlock exact per-app mAh from dumpsys batterystats."))
                !ShizukuEnergy.isGranted() ->
                    box.addView(pill(ctx, "Grant Shizuku") {
                        ShizukuEnergy.requestPermission()
                    })
                else -> {
                    // dumpsys batterystats can take seconds — run it OFF the
                    // main thread on tap, then post rows back. (Auto-running
                    // it on every page open would risk an ANR + is wasteful.)
                    ShizukuEnergy.bind()
                    val results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    box.addView(pill(ctx, "Load per-app (dumpsys)") {
                        results.removeAllViews()
                        results.addView(small(ctx, "Running dumpsys batterystats…"))
                        Thread {
                            val exact = runCatching { ShizukuEnergy.exact(ctx) }.getOrNull()
                            results.post {
                                results.removeAllViews()
                                when {
                                    exact == null ->
                                        results.addView(small(ctx, "Service not bound yet — tap again in a moment."))
                                    exact.isEmpty() ->
                                        results.addView(small(ctx, "No per-uid power section yet (needs discharge since last full charge)."))
                                    else -> exact.take(15).forEach { e ->
                                        results.addView(appRow(ctx, e.pkg, e.label, "%.1f mAh".format(e.mAh)))
                                    }
                                }
                            }
                        }.start()
                    })
                    box.addView(results)
                }
            }
        }

        // 4) Inside Cloud SuperApp (our own subsystem ledger)
        val (since, ledger) = EnergyLedger.snapshot()
        card(ctx, root, "Inside Cloud SuperApp") { box ->
            if (ledger.isEmpty()) {
                box.addView(small(ctx, "No subsystem activity recorded yet this session.")); return@card
            }
            box.addView(small(ctx, "CPU-time / wakeups / bytes per subsystem since " +
                (if (since > 0) fmtAgo(since) else "start")))
            ledger.entries.sortedByDescending { it.value.cpuNanos }.forEach { (tag, st) ->
                val cpuMs = st.cpuNanos / 1_000_000L
                val parts = buildString {
                    append("${cpuMs} ms")
                    if (st.wakeups > 0) append("  ·  ${st.wakeups} wake")
                    if (st.bytes > 0) append("  ·  ${sizeStr(st.bytes)}")
                }
                box.addView(line(ctx, tag, parts))
            }
        }

        // Actions
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val t = dp(ctx, 8); setPadding(0, t, 0, 0)
        }
        actions.addView(pill(ctx, "Refresh") {
            (view as? ScrollView)?.let { sv ->
                val rootCol = sv.getChildAt(0) as LinearLayout
                rootCol.removeAllViews(); build(ctx, rootCol)
            }
        })
        actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, 8), 1) })
        actions.addView(pill(ctx, "Reset window") {
            EnergyLedger.reset(); runCatching { EnergyStore(ctx).clear() }
            (view as? ScrollView)?.let { sv ->
                val rootCol = sv.getChildAt(0) as LinearLayout
                rootCol.removeAllViews(); build(ctx, rootCol)
            }
        })
        root.addView(actions)
    }

    // ── tiny view helpers ───────────────────────────────────────────
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

    private fun appRow(ctx: Context, pkg: String, label: String, value: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
        addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)).apply {
                marginEnd = dp(ctx, 10)
            }
            runCatching { setImageDrawable(ctx.packageManager.getApplicationIcon(pkg)) }
        })
        addView(TextView(ctx).apply {
            text = label; setTextColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = value; setTextColor(0xFFE9D8FD.toInt()); textSize = 13f
            typeface = Typeface.MONOSPACE
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
    private fun sizeStr(b: Long): String = when {
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }
    private fun fmtDur(ms: Long): String {
        val m = ms / 60_000L
        return when {
            m >= 60 -> "${m / 60}h ${m % 60}m"
            m >= 1 -> "${m}m"
            else -> "${ms / 1000L}s"
        }
    }
    private fun fmtAgo(sinceMs: Long): String {
        val mins = (System.currentTimeMillis() - sinceMs) / 60_000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ${mins % 60}m ago"
        }
    }

    companion object { const val TAG = "energy_usage_dialog" }
}
