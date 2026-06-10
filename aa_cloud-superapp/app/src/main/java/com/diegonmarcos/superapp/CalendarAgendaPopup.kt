package com.diegonmarcos.superapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Calendar agenda popup — shown when the user taps the date+time
 * center cell of the status strip. Renders the same 7-day list shape
 * as CalendarAgendaFragment (today + next 6 days), with the same
 * placeholder rows since libs:cal slice D (CalDAV) hasn't landed yet.
 *
 * Scrollable so the bubble stays compact even though the content has
 * a fixed 7-row layout. Today's row is highlighted (slightly brighter
 * background, bold header) so the user can read "what's next" at a
 * glance.
 *
 * Mirrors the visual shape of BatteryEstimatePopup / NetworkInfoPopup
 * / SystemInfoPopup so the four strip popups read as one family.
 */
object CalendarAgendaPopup {

    fun show(ctx: Context, anchor: View) {
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()

        // Headline
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(0xEE111111.toInt())
                setStroke(maxOf(1, (1 * d).toInt()), 0x44FFFFFF.toInt())
            }
        }
        container.addView(label(ctx, "Agenda · next 7 days"))
        val todayLabel = SimpleDateFormat("EEEE  d MMM yyyy", Locale.getDefault())
            .format(Calendar.getInstance().time)
        container.addView(valueBig(ctx, todayLabel))
        container.addView(spacer(ctx, (8 * d).toInt()))

        // Body — scrollable so the bubble caps height. fixed maxHeight =
        // 300dp keeps it compact on small screens.
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (300 * d).toInt(),
            )
            isVerticalScrollBarEnabled = true
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val dayHeaderFmt = SimpleDateFormat("EEE  d MMM", Locale.getDefault())
        val cal = Calendar.getInstance()
        for (i in 0 until 7) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val p = (6 * d).toInt(); setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (4 * d).toInt() }
                layoutParams = lp
                setBackgroundColor(if (i == 0) 0x441A0033 else 0x221A0033)
            }
            row.addView(TextView(ctx).apply {
                text = if (i == 0) "Today · ${dayHeaderFmt.format(cal.time)}"
                       else dayHeaderFmt.format(cal.time)
                setTextColor(if (i == 0) 0xFFE9D8FD.toInt() else 0xCCFFFFFF.toInt())
                textSize = 13f
                typeface = if (i == 0) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                           else Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            })
            row.addView(TextView(ctx).apply {
                text = "no events"
                setTextColor(0x77FFFFFF.toInt())
                textSize = 11f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, (2 * d).toInt(), 0, 0)
            })
            body.addView(row)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        body.addView(TextView(ctx).apply {
            text = "CalDAV via libs:cal lands next; until then this is a placeholder. Same source as section:cal/agenda."
            setTextColor(0x55FFFFFF.toInt())
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding((2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
        })
        scroll.addView(body)
        container.addView(scroll)

        val pw = PopupWindow(
            container,
            (260 * d).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8 * d
        }
        // Center the popup on-screen (NOT under the date/time anchor).
        // showAsDropDown anchors below the anchor view and can only
        // shift horizontally via Gravity flags; the result is still
        // anchored vertically to the strip and clips against the
        // status bar. showAtLocation with Gravity.CENTER ignores the
        // anchor's position entirely and places the popup at the
        // screen midpoint, which is what the user wants — the agenda
        // is consulted as a centerpiece, not as a tooltip on the
        // status strip. We still pass `anchor` only as the token-
        // bearing root (required for window manager dispatch).
        pw.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    private fun label(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        textSize = 11f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun valueBig(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 16f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}
