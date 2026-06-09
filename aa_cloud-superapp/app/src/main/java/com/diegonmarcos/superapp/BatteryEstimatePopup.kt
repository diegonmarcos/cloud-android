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
import android.widget.TextView

/**
 * Compact info bubble shown when the user taps the status-strip
 * battery icon. Surfaces the same battery-session metrics rendered
 * in Configs/About/Battery & Usage, with "Estimated battery last"
 * as the headline since that's what the user usually wants from a
 * one-glance tap.
 *
 * Anchored under the tapped icon (Gravity.END so the wider popup
 * extends leftward from the right-edge icon and stays on-screen).
 * Outside-touch + back dismiss. Stateless — each show() re-reads
 * via BatterySessionStats so a left-open popup never shows stale
 * numbers.
 */
object BatteryEstimatePopup {

    fun show(ctx: Context, anchor: View) {
        val s = BatterySessionStats.read(ctx)
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

        // Headline: wall-clock ETA — discharging shows "23:14 today"
        // (battery dies at), charging shows "07:42 tomorrow" (fully
        // charged at). Single field, label flips on charging state so
        // the user always reads the most actionable number first.
        container.addView(label(ctx, if (s.isCharging) "ETA full charge" else "ETA battery drained"))
        container.addView(valueBig(ctx, BatterySessionStats.fmtEtaWallClock(s)))
        container.addView(spacer(ctx, (10 * d).toInt()))
        container.addView(label(ctx, if (s.isCharging) "Estimated time to full" else "Estimated battery last"))
        container.addView(valueSmall(ctx, BatterySessionStats.fmtEtaDuration(s)))
        container.addView(spacer(ctx, (6 * d).toInt()))
        container.addView(label(ctx, if (s.isCharging) "Since plugged in" else "Since last charge"))
        container.addView(valueSmall(ctx, BatterySessionStats.fmtSinceAnchor(s)))
        container.addView(spacer(ctx, (6 * d).toInt()))
        container.addView(label(ctx, if (s.isCharging) "Charge power" else "Drain power"))
        container.addView(valueSmall(ctx, BatterySessionStats.fmtPowerRow(s)))
        container.addView(spacer(ctx, (6 * d).toInt()))
        container.addView(label(ctx, if (s.isCharging) "% battery / h gained" else "% battery / h consumed"))
        container.addView(valueSmall(ctx, BatterySessionStats.fmtRateUnified(s)))

        val pw = PopupWindow(
            container,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8 * d
        }
        // Gravity.END aligns the popup's right edge with the anchor's
        // right edge, so the wider bubble extends LEFTWARD instead of
        // off-screen to the right.
        pw.showAsDropDown(anchor, 0, (6 * d).toInt(), Gravity.END)
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
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private fun valueSmall(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}
