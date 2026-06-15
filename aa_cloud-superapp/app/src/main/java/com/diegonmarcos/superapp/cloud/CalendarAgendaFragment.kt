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
 * Self-contained agenda list — next 7 days, one row per day. No CalDAV
 * integration yet (lands with libs:cal slice D); each day shows "no
 * events" as a placeholder so the layout reads correctly.
 *
 * Used standalone (page:cal/agenda) and embedded inside
 * [AggregatorStackFragment] (Infos · Apps stack_apps = kind "calendar_agenda").
 */
class CalendarAgendaFragment : Fragment() {

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

        val dayHeaderFmt = SimpleDateFormat("EEE  d MMM", Locale.getDefault())
        val cal = Calendar.getInstance()

        for (i in 0 until 7) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(6); setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
                layoutParams = lp
                setBackgroundColor(if (i == 0) 0x331A0033 else 0x221A0033)
            }
            row.addView(TextView(ctx).apply {
                text = if (i == 0) "Today · ${dayHeaderFmt.format(cal.time)}"
                       else dayHeaderFmt.format(cal.time)
                setTextColor(if (i == 0) 0xFFE9D8FD.toInt() else 0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            })
            row.addView(TextView(ctx).apply {
                text = "no events"
                setTextColor(0x77FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(2), 0, 0)
            })
            root.addView(row)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        root.addView(TextView(ctx).apply {
            text = "CalDAV integration lands with libs:cal slice D — once wired, events from your declared calendars populate here."
            setTextColor(0x55FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(dp(2), dp(10), dp(2), dp(2))
        })

        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): CalendarAgendaFragment = CalendarAgendaFragment()
    }
}
