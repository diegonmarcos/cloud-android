package com.diegonmarcos.superapp.datamanager

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data Usage manager — opened from Configs/About → Network, the same
 * "open the full data screen" affordance the Firewall section uses.
 *
 * Three axes, all answered live by [DataUsageProvider] over
 * NetworkStatsManager:
 *   • per PERIOD — Today / This month / Last 7 days / Last 30 days,
 *     switched by the pill row at the top.
 *   • per NETWORK TYPE — mobile vs Wi-Fi, split rx/tx, in the summary card.
 *   • per APP — ranked list; tap a row to expand a daily breakdown with
 *     proportional bars for the selected period.
 *
 * Nothing is persisted: the OS keeps this history itself, so a local copy
 * would only be a second, staler truth.
 *
 * Programmatic dark views (no resource deps) so it can live lib-side,
 * matching EnergyUsageDialog / FirewallDialog.
 */
class DataUsageDialog : DialogFragment() {

    /** Selectable window. `days` < 0 means "calendar month to date". */
    private data class Period(val label: String, val days: Int)

    private val periods = listOf(
        Period("Today", 0),
        Period("This month", -1),
        Period("Last 7 days", 7),
        Period("Last 30 days", 30),
    )
    private var selected = 0

    /** uid of the row currently expanded into a daily breakdown, if any. */
    private var expandedUid: Int? = null

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

    /** Re-render in place after a period switch or a row expand/collapse. */
    private fun rebuild() {
        val scroll = view as? ScrollView ?: return
        val root = scroll.getChildAt(0) as? LinearLayout ?: return
        root.removeAllViews()
        build(scroll.context, root)
    }

    private fun windowStart(now: Long): Long {
        val p = periods[selected]
        return when {
            p.days < 0 -> DataUsageProvider.startOfMonth(now)
            p.days == 0 -> DataUsageProvider.startOfToday(now)
            else -> DataUsageProvider.startOfDaysAgo(p.days - 1, now)
        }
    }

    private fun build(ctx: Context, root: LinearLayout) {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = "Data Usage"
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

        // Usage access is the ONE gate. Reuse the existing grant flow —
        // the same settings screen Configs/About → Permissions opens.
        if (!DataUsageProvider.hasUsageAccess(ctx)) {
            card(ctx, root, "Usage Access required") { box ->
                box.addView(small(ctx, "Per-app data usage is read from NetworkStatsManager, which needs the Usage Access special grant. It's the same grant the battery per-app estimate and the Phone smart folders use — granting it once unlocks all of them."))
                box.addView(pill(ctx, "Open Usage Access settings") {
                    runCatching {
                        startActivity(android.content.Intent(DataUsageProvider.USAGE_ACCESS_SETTINGS))
                    }
                })
                box.addView(pill(ctx, "Recheck") { rebuild() })
            }
            return
        }

        // Period selector.
        val pillRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        periods.forEachIndexed { i, p ->
            pillRow.addView(pill(ctx, p.label, active = i == selected) {
                selected = i; expandedUid = null; rebuild()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(ctx, 6) }
            })
        }
        root.addView(pillRow)

        val now = System.currentTimeMillis()
        val start = windowStart(now)
        val device = runCatching { DataUsageProvider.deviceTotals(ctx, start, now) }
            .getOrDefault(DataUsageProvider.Totals())
        val apps = runCatching { DataUsageProvider.perApp(ctx, start, now) }
            .getOrDefault(emptyList())

        // 1) Summary — the per-network-type axis.
        card(ctx, root, "${periods[selected].label}  ·  since ${fmtStamp(start)}") { box ->
            big(ctx, box, DataUsageProvider.fmtBytes(device.total),
                "whole device, mobile + Wi-Fi")
            box.addView(line(ctx, "Mobile", DataUsageProvider.fmtBytes(device.mobile)))
            box.addView(split(ctx, device.mobileRx, device.mobileTx))
            box.addView(line(ctx, "Wi-Fi", DataUsageProvider.fmtBytes(device.wifi)))
            box.addView(split(ctx, device.wifiRx, device.wifiTx))
            if (device.total > 0) {
                box.addView(bar(ctx, device.mobile, device.total, 0xFFF6AD55.toInt()))
                box.addView(small(ctx, "orange bar = mobile share of the period"))
            }
            box.addView(small(ctx, "Device totals come from querySummaryForDevice, so they include traffic no app owns (tethering, kernel sockets) and read slightly higher than the per-app sum below."))
        }

        // 2) Per-app ranked list — the per-app axis, with drill-down.
        card(ctx, root, "By app  ·  ${apps.size} apps with traffic") { box ->
            if (apps.isEmpty()) {
                box.addView(small(ctx, "No per-app traffic recorded in this window."))
                return@card
            }
            val peak = apps.first().totals.total.coerceAtLeast(1L)
            apps.forEach { a ->
                box.addView(appRow(ctx, a) {
                    expandedUid = if (expandedUid == a.uid) null else a.uid
                    rebuild()
                })
                box.addView(bar(ctx, a.totals.total, peak, 0xFF9F7AEA.toInt()))
                if (expandedUid == a.uid) box.addView(drillDown(ctx, a, start, now))
            }
            box.addView(small(ctx, "Tap an app for its day-by-day breakdown. Apps sharing a Linux uid are reported by the kernel as one bucket and can't be split apart."))
        }
    }

    /** Expanded per-app daily breakdown for the selected period. */
    private fun drillDown(
        ctx: Context, a: DataUsageProvider.AppUsage, start: Long, end: Long,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 10), dp(ctx, 6), 0, dp(ctx, 10))
        addView(line(ctx, "Mobile", DataUsageProvider.fmtBytes(a.totals.mobile)))
        addView(split(ctx, a.totals.mobileRx, a.totals.mobileTx))
        addView(line(ctx, "Wi-Fi", DataUsageProvider.fmtBytes(a.totals.wifi)))
        addView(split(ctx, a.totals.wifiRx, a.totals.wifiTx))
        addView(small(ctx, "Daily —"))
        val daily = runCatching { DataUsageProvider.dailyForUid(ctx, a.uid, start, end) }
            .getOrDefault(emptyList())
        val peak = daily.maxOfOrNull { it.totals.total }?.coerceAtLeast(1L) ?: 1L
        if (daily.isEmpty()) {
            addView(small(ctx, "No daily buckets in this window."))
        } else {
            daily.forEach { d ->
                addView(line(ctx, fmtDay(d.startMs), DataUsageProvider.fmtBytes(d.totals.total)))
                addView(bar(ctx, d.totals.total, peak, 0xFF63B3ED.toInt()))
            }
        }
        addView(small(ctx, a.pkg))
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

    /** "↓ 12.4 MB   ↑ 900 KB" sub-line under a transport row. */
    private fun split(ctx: Context, rx: Long, tx: Long) = TextView(ctx).apply {
        text = "↓ ${DataUsageProvider.fmtBytes(rx)}    ↑ ${DataUsageProvider.fmtBytes(tx)}"
        setTextColor(0x88FFFFFF.toInt()); textSize = 11f
        typeface = Typeface.MONOSPACE; gravity = Gravity.END
        setPadding(0, 0, 0, dp(ctx, 4))
    }

    private fun appRow(ctx: Context, a: DataUsageProvider.AppUsage, onClick: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            isClickable = true; setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)).apply {
                    marginEnd = dp(ctx, 10)
                }
                runCatching { setImageDrawable(ctx.packageManager.getApplicationIcon(a.pkg)) }
            })
            addView(TextView(ctx).apply {
                text = a.label; setTextColor(Color.WHITE); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                text = DataUsageProvider.fmtBytes(a.totals.total)
                setTextColor(0xFFE9D8FD.toInt()); textSize = 13f
                typeface = Typeface.MONOSPACE
            })
        }

    /** Proportional horizontal bar built from layout weights — no drawing
     *  code and no charting dependency. */
    private fun bar(ctx: Context, value: Long, peak: Long, color: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)
        ).apply { topMargin = dp(ctx, 2); bottomMargin = dp(ctx, 4) }
        val filled = value.coerceIn(0L, peak).toFloat()
        addView(View(ctx).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, filled)
        })
        addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT,
                (peak - filled).coerceAtLeast(0.0001f))
        })
    }

    private fun pill(
        ctx: Context, label: String, active: Boolean = false, onClick: () -> Unit,
    ) = TextView(ctx).apply {
        text = label; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 20).toFloat()
            setColor(if (active) 0xFF6B46C1.toInt() else 0xFF3B2A63.toInt())
        }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun small(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; setTextColor(0x77FFFFFF.toInt()); textSize = 11f
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 4))
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun fmtStamp(ts: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))

    private fun fmtDay(ts: Long): String =
        SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(Date(ts))

    companion object { const val TAG = "data_usage_dialog" }
}
