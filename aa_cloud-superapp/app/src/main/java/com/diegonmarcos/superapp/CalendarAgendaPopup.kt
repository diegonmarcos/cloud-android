package com.diegonmarcos.superapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    // Stopwatch state — kept in static fields so the timer keeps
    // running while the popup is closed. SystemClock.elapsedRealtime
    // is monotonic across sleeps so the elapsed value is accurate
    // even if the user dismisses the popup, locks the screen, and
    // re-opens it minutes later.
    @Volatile private var stopwatchRunning: Boolean = false
    @Volatile private var stopwatchStartElapsedMs: Long = 0L
    @Volatile private var stopwatchAccumulatedMs: Long = 0L

    private fun stopwatchCurrentMs(): Long =
        if (stopwatchRunning)
            stopwatchAccumulatedMs + (SystemClock.elapsedRealtime() - stopwatchStartElapsedMs)
        else
            stopwatchAccumulatedMs

    fun show(ctx: Context, anchor: View) {
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(0xEE111111.toInt())
                setStroke(maxOf(1, (1 * d).toInt()), 0x44FFFFFF.toInt())
            }
        }

        // Headline row — left: label + today's date (vertical stack);
        // right: stopwatch widget (elapsed time + Start/Stop toggle).
        // Horizontal LinearLayout with weight=1 on the left side so
        // the stopwatch hugs the right edge regardless of date length.
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val headerLeft = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLeft.addView(label(ctx, "Agenda · next 7 days"))
        val todayLabel = SimpleDateFormat("EEEE  d MMM yyyy", Locale.getDefault())
            .format(Calendar.getInstance().time)
        headerLeft.addView(valueBig(ctx, todayLabel))
        headerRow.addView(headerLeft)
        headerRow.addView(buildStopwatch(ctx, d))
        container.addView(headerRow)
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
        // Horizontally SCREEN-centered, vertically dropped from the
        // status strip. showAsDropDown is the right vertical
        // primitive (matches Battery/SysInfo exactly — same
        // sub-strip drop). For horizontal centering we compute
        // xOffset so the popup's left edge lands at
        // (screenWidth − popupWidth) / 2 regardless of the date/
        // time anchor's actual screen-x position (which on this
        // device is right-of-center, so Gravity.CENTER_HORIZONTAL
        // alone biased the popup rightward).
        val anchorLoc = IntArray(2); anchor.getLocationOnScreen(anchorLoc)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val popupW  = (260 * d).toInt()
        val xOffset = (screenW - popupW) / 2 - anchorLoc[0]
        pw.showAsDropDown(anchor, xOffset, (6 * d).toInt(), Gravity.START)
    }

    /** Top-right stopwatch widget: elapsed mm:ss.t + Start/Stop
     *  toggle button. Vertical stack — time on top, button below.
     *  Wired to module-level state so the timer persists across
     *  popup open/close cycles. Tick handler runs while attached
     *  to the window and auto-cancels in onDetachedFromWindow,
     *  which fires when the PopupWindow dismisses. */
    private fun buildStopwatch(ctx: Context, d: Float): View {
        // Horizontal: BIG time digits + small flat minimalist toggle
        // glyph button immediately to its right. Reads as a single
        // unit anchored to the top-right of the header. Time
        // dominates visually (20sp bold); button is a 22dp solid
        // circle with a play/pause glyph — no gradient, no border,
        // no rounded-rect text "Start"/"Stop" — just intent at a
        // glance.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * d).toInt() }
        }
        val timeView = TextView(ctx).apply {
            text = formatStopwatch(stopwatchCurrentMs())
            setTextColor(0xFFE9D8FD.toInt())
            textSize = 20f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        val runningGlyph = "■"
        val stoppedGlyph = "▶"
        val runningColor = 0xFFB91C1C.toInt()
        val stoppedColor = 0xFF16A34A.toInt()
        val btn = TextView(ctx).apply {
            text = if (stopwatchRunning) runningGlyph else stoppedGlyph
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            val sz = (22 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = (8 * d).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (stopwatchRunning) runningColor else stoppedColor)
            }
        }
        btn.setOnClickListener {
            if (stopwatchRunning) {
                stopwatchAccumulatedMs += SystemClock.elapsedRealtime() - stopwatchStartElapsedMs
                stopwatchRunning = false
                btn.text = stoppedGlyph
                (btn.background as? GradientDrawable)?.setColor(stoppedColor)
            } else {
                stopwatchStartElapsedMs = SystemClock.elapsedRealtime()
                stopwatchRunning = true
                btn.text = runningGlyph
                (btn.background as? GradientDrawable)?.setColor(runningColor)
            }
            timeView.text = formatStopwatch(stopwatchCurrentMs())
        }
        // Long-press the time → reset to 0. Lets the user start
        // fresh without needing a third button.
        timeView.setOnLongClickListener {
            stopwatchRunning = false
            stopwatchAccumulatedMs = 0L
            stopwatchStartElapsedMs = 0L
            timeView.text = formatStopwatch(0L)
            btn.text = stoppedGlyph
            (btn.background as? GradientDrawable)?.setColor(stoppedColor)
            true
        }
        // 100ms tick while attached. Detaches automatically when
        // the PopupWindow dismisses; no leak risk.
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private val handler = Handler(Looper.getMainLooper())
            private val runnable = object : Runnable {
                override fun run() {
                    timeView.text = formatStopwatch(stopwatchCurrentMs())
                    handler.postDelayed(this, 100L)
                }
            }
            override fun onViewAttachedToWindow(v: View) { handler.post(runnable) }
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(runnable) }
        })
        row.addView(timeView)
        row.addView(btn)
        return row
    }

    private fun formatStopwatch(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        val tenths = (ms % 1000L) / 100L
        return "%d:%02d.%d".format(m, s, tenths)
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
