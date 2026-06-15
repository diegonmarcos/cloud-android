package com.diegonmarcos.superapp.cloud

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Tasks aggregator panel — a tab strip on top (Agenda · Day · ToDo)
 * + a swappable body below. Each tab is a self-contained placeholder
 * until CalDAV / Tasks integration lands; the panel exists so the
 * Infos · Apps surface gets the three views the user wants from one
 * card.
 *
 * Embedded inside [AggregatorStackFragment] (kind = "tasks").
 */
class TasksFragment : Fragment() {

    private enum class Tab { AGENDA, DAY, TODO }
    private var active: Tab = Tab.AGENDA

    private lateinit var chipAgenda: TextView
    private lateinit var chipDay:    TextView
    private lateinit var chipToDo:   TextView
    private lateinit var body:       LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // ── Chip strip ──
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(2); setPadding(pad, pad, pad, dp(6))
        }
        chipAgenda = chip(ctx, "Agenda") { setActive(Tab.AGENDA) }
        chipDay    = chip(ctx, "Day")    { setActive(Tab.DAY) }
        chipToDo   = chip(ctx, "ToDo")   { setActive(Tab.TODO) }
        chips.addView(chipAgenda)
        chips.addView(chipDay)
        chips.addView(chipToDo)
        root.addView(chips)

        // ── Body ──
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)

        setActive(active)
        return root
    }

    private fun setActive(t: Tab) {
        active = t
        // Update chip styling.
        for ((chip, target) in listOf(chipAgenda to Tab.AGENDA, chipDay to Tab.DAY, chipToDo to Tab.TODO)) {
            val sel = (target == t)
            chip.setTextColor(if (sel) 0xFFE9D8FD.toInt() else 0x88FFFFFF.toInt())
            chip.setBackgroundColor(if (sel) 0x55B794F4.toInt() else 0x22FFFFFF)
            chip.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        // Re-render body.
        body.removeAllViews()
        val ctx = requireContext()
        when (t) {
            Tab.AGENDA -> renderAgenda(ctx, body)
            Tab.DAY    -> renderDay(ctx, body)
            Tab.TODO   -> renderToDo(ctx, body)
        }
    }

    // ── Renderers ────────────────────────────────────────────────────────

    private fun renderAgenda(ctx: android.content.Context, host: LinearLayout) {
        val fmt = SimpleDateFormat("EEE  d MMM", Locale.getDefault())
        val cal = Calendar.getInstance()
        for (i in 0 until 7) {
            val row = simpleRow(ctx,
                title = if (i == 0) "Today · ${fmt.format(cal.time)}" else fmt.format(cal.time),
                body  = "no events",
                highlight = i == 0,
            )
            host.addView(row)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        host.addView(caption(ctx,
            "CalDAV integration lands with libs:cal — declared calendars populate events here."))
    }

    private fun renderDay(ctx: android.content.Context, host: LinearLayout) {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY)
        // 8am → 10pm hourly grid.
        for (h in 8..22) {
            val label = "%02d:00".format(h)
            host.addView(simpleRow(ctx,
                title = label + (if (h == now) "  · now" else ""),
                body  = if (h == now) "no events right now" else "·",
                highlight = h == now,
            ))
        }
        host.addView(caption(ctx,
            "Hour-by-hour for today. Once CalDAV is wired each row shows events that overlap that slot."))
    }

    private fun renderToDo(ctx: android.content.Context, host: LinearLayout) {
        // Placeholder ToDo list. Real ToDo backend (CalDAV VTODO or
        // Tasks API) lands later; for now show a single illustrative row.
        host.addView(simpleRow(ctx,
            title     = "(no tasks yet)",
            body      = "Sample row — long-press for actions when the ToDo store wires in.",
            highlight = false,
        ))
        host.addView(caption(ctx,
            "ToDo persistence pending — will surface VTODO items from your CalDAV server + a local quick-task store."))
    }

    // ── Widget helpers ───────────────────────────────────────────────────

    private fun chip(ctx: android.content.Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4) }
            layoutParams = lp
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun simpleRow(ctx: android.content.Context, title: String, body: String, highlight: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(6); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            layoutParams = lp
            setBackgroundColor(if (highlight) 0x331A0033 else 0x221A0033)
        }
        row.addView(TextView(ctx).apply {
            text = title
            setTextColor(if (highlight) 0xFFE9D8FD.toInt() else 0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            typeface = if (highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        })
        row.addView(TextView(ctx).apply {
            text = body
            setTextColor(0x77FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0x55FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(dp(2), dp(10), dp(2), dp(2))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): TasksFragment = TasksFragment()
    }
}
