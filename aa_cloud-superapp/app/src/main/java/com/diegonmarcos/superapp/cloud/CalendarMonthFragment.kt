package com.diegonmarcos.superapp.cloud

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Calendar
import java.util.Locale

/**
 * Self-contained month grid (Sun-first, 6 rows × 7 columns). No CalDAV
 * integration yet — that lands with libs:cal slice D. Today is highlighted
 * with a circular badge tinted with the brand colour.
 *
 * Used standalone (page:cal/month) and embedded inside [AggregatorStackFragment]
 * (Infos · Apps stack_apps[0] = kind "calendar_month").
 */
class CalendarMonthFragment : Fragment() {

    /** Year, month (0-based — Calendar.MONTH convention). */
    private var displayYear:  Int = 0
    private var displayMonth: Int = 0

    private lateinit var titleView: TextView
    private lateinit var gridContainer: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val today = Calendar.getInstance()
        displayYear  = today.get(Calendar.YEAR)
        displayMonth = today.get(Calendar.MONTH)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Header: ◀  Month YYYY  ▶
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(4); setPadding(pad, pad, pad, pad)
        }
        val prev = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            rotation = 180f
            background = null
            val sz = dp(36); layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { shiftMonth(-1) }
        }
        titleView = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val next = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            background = null
            val sz = dp(36); layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { shiftMonth(+1) }
        }
        header.addView(prev); header.addView(titleView); header.addView(next)
        root.addView(header)

        // Day-of-week strip (S M T W T F S — locale-aware short names).
        val dowStrip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(2); setPadding(pad, pad, pad, pad)
        }
        val short = java.text.DateFormatSymbols(Locale.getDefault()).shortWeekdays
        // Calendar.SUNDAY = 1 ... SATURDAY = 7
        for (dow in Calendar.SUNDAY..Calendar.SATURDAY) {
            val cell = TextView(ctx).apply {
                text = short[dow].take(1)
                gravity = Gravity.CENTER
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                alpha = 0.65f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            dowStrip.addView(cell)
        }
        root.addView(dowStrip)

        // Grid container — populated by drawMonth().
        gridContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gridContainer)

        drawMonth()
        return root
    }

    private fun shiftMonth(delta: Int) {
        displayMonth += delta
        if (displayMonth < 0)  { displayMonth = 11; displayYear-- }
        if (displayMonth > 11) { displayMonth = 0;  displayYear++ }
        drawMonth()
    }

    private fun drawMonth() {
        val ctx = requireContext()
        gridContainer.removeAllViews()

        val cal = Calendar.getInstance().apply {
            clear(); set(Calendar.YEAR, displayYear); set(Calendar.MONTH, displayMonth)
        }
        titleView.text = java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)

        // Offset of the 1st (Calendar.SUNDAY=1 → col 0, MONDAY=2 → col 1, …).
        val firstDow      = cal.get(Calendar.DAY_OF_WEEK)         // 1..7
        val daysInMonth   = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val leadingBlanks = firstDow - Calendar.SUNDAY            // 0..6

        val today    = Calendar.getInstance()
        val isCurMo  = today.get(Calendar.YEAR) == displayYear &&
                       today.get(Calendar.MONTH) == displayMonth
        val todayDom = if (isCurMo) today.get(Calendar.DAY_OF_MONTH) else -1

        var day = 1
        for (row in 0 until 6) {
            val rowLay = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 7) {
                val flat = row * 7 + col
                val cell = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                    val m = dp(2); setPadding(m, m, m, m)
                }
                if (flat < leadingBlanks || day > daysInMonth) {
                    cell.text = ""
                } else {
                    cell.text = day.toString()
                    if (day == todayDom) {
                        cell.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(resources.getColor(R.color.cloud_primary, ctx.theme))
                        }
                        cell.setTextColor(0xFFFFFFFF.toInt())
                        cell.typeface = Typeface.DEFAULT_BOLD
                    }
                    val capturedDay = day
                    cell.setOnClickListener { onDayTapped(capturedDay) }
                    day++
                }
                rowLay.addView(cell)
            }
            gridContainer.addView(rowLay)
            if (day > daysInMonth) break  // Trim trailing empty rows
        }
    }

    /** Placeholder for day-tap; future CalDAV slice surfaces the day agenda. */
    private fun onDayTapped(dom: Int) {
        // No-op for now. When libs:cal lands an agenda fragment, this will
        // push it on top with the (year, month, dom) selection.
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = CalendarMonthFragment() }
}
