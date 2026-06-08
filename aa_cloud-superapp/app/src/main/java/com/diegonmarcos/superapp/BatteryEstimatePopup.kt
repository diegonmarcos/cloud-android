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

        container.addView(label(ctx, "Estimated battery last"))
        container.addView(valueBig(ctx, BatterySessionStats.fmtEta(s)))
        container.addView(spacer(ctx, (10 * d).toInt()))
        container.addView(label(ctx, "Since last charge"))
        container.addView(valueSmall(ctx, BatterySessionStats.fmtSinceLastCharge(s)))
        container.addView(spacer(ctx, (6 * d).toInt()))
        container.addView(label(ctx, "% battery / min consumed"))
        container.addView(valueSmall(ctx, BatterySessionStats.fmtRate(s)))

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
