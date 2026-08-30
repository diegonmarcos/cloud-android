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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data Usage manager — opened from Configs/About → Network, the same
 * "open the full data screen" affordance the Firewall section uses.
 *
 * Four axes:
 *   • per PERIOD — LIVE windows (Today / This month / 7d / 30d) answered
 *     by [DataUsageProvider] straight from NetworkStatsManager, plus
 *     STORED months from [DataUsageHistoryStore] which outlive the OS's
 *     ~90-day retention. The two pill rows pick between them.
 *   • per NETWORK TYPE — mobile vs Wi-Fi, rx/tx split.
 *   • per SIM — one row (and a chip, once more than one subscription is
 *     known) per active subscription, labelled carrier + slot.
 *   • per APP — ranked list; in a live window each row expands into a
 *     daily breakdown. Stored months keep only the top
 *     [DataUsageHistoryStore.TOP_APPS] apps, so those rows don't expand.
 *
 * Live windows persist nothing — the OS owns that history. Only the
 * monthly roll-up is stored, because that is precisely what the OS throws
 * away.
 *
 * Programmatic dark views (no resource deps) so it can live lib-side,
 * matching EnergyUsageDialog / FirewallDialog.
 */
class DataUsageDialog : DialogFragment() {

    /** Selectable live window. `days` < 0 means "calendar month to date". */
    private data class Period(val label: String, val days: Int)

    private val periods = listOf(
        Period("Today", 0),
        Period("This month", -1),
        Period("Last 7 days", 7),
        Period("Last 30 days", 30),
    )

    /** Either a live period index, or a stored "yyyy-MM" key. Exactly one
     *  is non-null at any time. */
    private var selectedPeriod: Int? = 0
    private var selectedMonth: String? = null

    /** uid of the row currently expanded into a daily breakdown, if any. */
    private var expandedUid: Int? = null

    /** Standard runtime-permission contract — the same androidx contract
     *  PermissionsFragment drives its bulk request with. */
    private val phoneStatePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { rebuild() }

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
        // Fold the current month in on open too, so history still accumulates
        // for a user whose device never lets the 15-min worker run.
        runCatching { DataUsageHistoryStore.refresh(ctx) }
        build(ctx, root)
        return scroll
    }

    /** Re-render in place after a selection change or a grant. */
    private fun rebuild() {
        val scroll = view as? ScrollView ?: return
        val root = scroll.getChildAt(0) as? LinearLayout ?: return
        root.removeAllViews()
        build(scroll.context, root)
    }

    private fun windowStart(now: Long): Long {
        val p = periods[selectedPeriod ?: 0]
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

        // Usage access is the ONE hard gate. Reuse the existing grant flow —
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

        // Live-window selector.
        val liveRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        periods.forEachIndexed { i, p ->
            liveRow.addView(weighted(ctx, pill(ctx, p.label, active = selectedPeriod == i) {
                selectedPeriod = i; selectedMonth = null; expandedUid = null; rebuild()
            }))
        }
        root.addView(liveRow)

        // Stored-month picker — the history the OS no longer has.
        val stored = runCatching { DataUsageHistoryStore.months(ctx) }.getOrDefault(emptyList())
        if (stored.isNotEmpty()) {
            root.addView(small(ctx, "Stored months — kept beyond the OS's ~90-day retention"))
            val monthRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            stored.forEach { m ->
                monthRow.addView(pill(ctx, DataUsageHistoryStore.monthLabel(m.month),
                    active = selectedMonth == m.month) {
                    selectedMonth = m.month; selectedPeriod = null; expandedUid = null; rebuild()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(ctx, 6) }
                })
            }
            root.addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(monthRow)
            })
        }

        val month = selectedMonth
        if (month != null) buildStoredMonth(ctx, root, month) else buildLive(ctx, root)
    }

    // ── stored month ──────────────────────────────────────────────────
    private fun buildStoredMonth(ctx: Context, root: LinearLayout, month: String) {
        val row = runCatching { DataUsageHistoryStore.months(ctx) }.getOrDefault(emptyList())
            .firstOrNull { it.month == month }
        val label = DataUsageHistoryStore.monthLabel(month)

        card(ctx, root, "$label  ·  stored") { box ->
            if (row == null) { box.addView(small(ctx, "No stored row.")); return@card }
            val t = row.totals
            big(ctx, box, DataUsageProvider.fmtBytes(t.total), "whole device, mobile + Wi-Fi")
            box.addView(line(ctx, "Mobile", DataUsageProvider.fmtBytes(t.mobile)))
            box.addView(split(ctx, t.mobileRx, t.mobileTx))
            box.addView(line(ctx, "Wi-Fi", DataUsageProvider.fmtBytes(t.wifi)))
            box.addView(split(ctx, t.wifiRx, t.wifiTx))
            if (t.total > 0) box.addView(bar(ctx, t.mobile, t.total, 0xFFF6AD55.toInt()))
            box.addView(small(ctx, "Month totals are exact and kept permanently — the OS itself may no longer hold this month."))
        }

        val subs = runCatching { DataUsageHistoryStore.subsOf(ctx, month) }.getOrDefault(emptyList())
        if (subs.isNotEmpty()) {
            card(ctx, root, "By SIM  ·  $label") { box ->
                val peak = subs.maxOf { it.total }.coerceAtLeast(1L)
                subs.forEach { s ->
                    box.addView(line(ctx, "${s.label}  (slot ${s.slot + 1})",
                        DataUsageProvider.fmtBytes(s.total)))
                    box.addView(split(ctx, s.rx, s.tx))
                    box.addView(bar(ctx, s.total, peak, 0xFF68D391.toInt()))
                }
                box.addView(small(ctx, "Carrier and slot are recorded at the time of the month, so history stays readable across SIM swaps."))
            }
        }

        val apps = runCatching { DataUsageHistoryStore.appsOf(ctx, month) }.getOrDefault(emptyList())
        card(ctx, root, "By app  ·  top ${DataUsageHistoryStore.TOP_APPS} of $label") { box ->
            if (apps.isEmpty()) {
                box.addView(small(ctx, "No stored per-app rows for this month — they're kept for the most recent ${DataUsageHistoryStore.MAX_APP_MONTHS} months only."))
                return@card
            }
            val peak = apps.first().bytes.coerceAtLeast(1L)
            apps.forEach { a ->
                box.addView(storedAppRow(ctx, a.pkg, a.bytes))
                box.addView(bar(ctx, a.bytes, peak, 0xFF9F7AEA.toInt()))
            }
            box.addView(small(ctx, "Only the top ${DataUsageHistoryStore.TOP_APPS} apps are stored per month; the month total above stays exact regardless."))
        }
    }

    // ── live window ───────────────────────────────────────────────────
    private fun buildLive(ctx: Context, root: LinearLayout) {
        val now = System.currentTimeMillis()
        val start = windowStart(now)
        val device = runCatching { DataUsageProvider.deviceTotals(ctx, start, now) }
            .getOrDefault(DataUsageProvider.Totals())
        val apps = runCatching { DataUsageProvider.perApp(ctx, start, now) }
            .getOrDefault(emptyList())

        // 1) Summary — the per-network-type axis.
        card(ctx, root, "${periods[selectedPeriod ?: 0].label}  ·  since ${fmtStamp(start)}") { box ->
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

        // 2) Per-SIM breakdown.
        buildSimCard(ctx, root, start, now)

        // 3) Per-app ranked list with drill-down.
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

    /** Per-subscription card, with a tap-to-grant row when READ_PHONE_STATE
     *  is missing. Degrades to the aggregate mobile figure, never hides. */
    private fun buildSimCard(ctx: Context, root: LinearLayout, start: Long, end: Long) {
        card(ctx, root, "By SIM") { box ->
            if (!DataUsageProvider.hasPhoneState(ctx)) {
                box.addView(small(ctx, "Splitting mobile data per SIM / eSIM needs the Phone permission (READ_PHONE_STATE) to list your active subscriptions. Without it only the aggregate mobile total above is available."))
                box.addView(pill(ctx, "Grant Phone permission") {
                    runCatching {
                        phoneStatePermission.launch(android.Manifest.permission.READ_PHONE_STATE)
                    }
                })
                return@card
            }
            val subs = runCatching { DataUsageProvider.mobilePerSubscription(ctx, start, end) }
                .getOrDefault(emptyList())
            if (subs.isEmpty()) {
                box.addView(small(ctx, "No active mobile subscription — Wi-Fi only, or no SIM present."))
                return@card
            }
            // Chips only earn their space once there's more than one SIM.
            if (subs.size > 1) {
                val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                subs.forEach { s ->
                    chips.addView(weighted(ctx, pill(ctx,
                        "${simName(s)} · ${DataUsageProvider.fmtBytes(s.totals.mobile)}",
                        active = true) { }))
                }
                box.addView(chips)
            }
            val peak = subs.maxOf { it.totals.mobile }.coerceAtLeast(1L)
            subs.forEach { s ->
                box.addView(line(ctx, "${simName(s)}  (slot ${s.slot + 1})",
                    DataUsageProvider.fmtBytes(s.totals.mobile)))
                box.addView(split(ctx, s.totals.mobileRx, s.totals.mobileTx))
                box.addView(bar(ctx, s.totals.mobile, peak, 0xFF68D391.toInt()))
            }
            if (subs.any { !it.exact }) {
                box.addView(small(ctx, "⚠ Android 10+ restricts the subscriber id (IMSI) to privileged apps, so an exact per-SIM split isn't possible here — each subscription shows the COMBINED mobile total, not its own share. The monthly ledger stores that same honest figure rather than inventing a division."))
            } else {
                box.addView(small(ctx, "Per-subscription totals, folded into the monthly ledger labelled by carrier + slot so they survive SIM swaps."))
            }
        }
    }

    private fun simName(s: DataUsageProvider.SubUsage): String =
        s.carrier.ifBlank { "SIM ${s.slot + 1}" }

    /** Expanded per-app daily breakdown for the selected live window. */
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

    /** Give a view equal weight in a horizontal row. */
    private fun weighted(ctx: Context, v: View): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(ctx, 6) }
    }

    private fun appRow(ctx: Context, a: DataUsageProvider.AppUsage, onClick: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            isClickable = true; setOnClickListener { onClick() }
            addView(icon(ctx, a.pkg))
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

    /** Row for a stored month — only pkg + bytes were kept, and the app may
     *  since have been uninstalled, so the label falls back to the package. */
    private fun storedAppRow(ctx: Context, pkg: String, bytes: Long) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
        addView(icon(ctx, pkg))
        addView(TextView(ctx).apply {
            text = runCatching {
                ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            setTextColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = DataUsageProvider.fmtBytes(bytes)
            setTextColor(0xFFE9D8FD.toInt()); textSize = 13f
            typeface = Typeface.MONOSPACE
        })
    }

    private fun icon(ctx: Context, pkg: String) = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)).apply {
            marginEnd = dp(ctx, 10)
        }
        runCatching { setImageDrawable(ctx.packageManager.getApplicationIcon(pkg)) }
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
