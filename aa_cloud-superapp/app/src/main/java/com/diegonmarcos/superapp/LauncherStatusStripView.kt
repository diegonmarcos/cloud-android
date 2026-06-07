package com.diegonmarcos.superapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom top status strip rendered ONLY when the SuperApp is acting
 * as the Android default launcher AND the active LauncherTheme is
 * Cloud. Replaces the system status bar (which MainActivity hides via
 * WindowInsetsController in launcher mode) with our own slim row
 * showing just two values:
 *
 *   • Time     (left)  — kept current via ACTION_TIME_TICK every
 *                        minute, plus TIMEZONE_CHANGED / TIME_SET so
 *                        manual edits show up immediately.
 *   • Battery  (right) — ACTION_BATTERY_CHANGED sticky broadcast.
 *
 * Android does not let an app remove individual icons from the
 * system status bar, so we hide the whole system bar and draw this
 * minimal substitute — matches the user's spec ("only show Time and
 * Battery") without faking system UI.
 */
class LauncherStatusStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private val timeView: TextView
    private val batteryView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val px = (12 * resources.displayMetrics.density).toInt()
        val py = (3 * resources.displayMetrics.density).toInt()
        setPadding(px, py, px, py)
        // Black wash so the white text reads cleanly regardless of
        // the GalaxyBackdrop visible underneath.
        setBackgroundColor(0xCC000000.toInt())

        timeView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        batteryView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            )
        }
        addView(timeView)
        addView(batteryView)
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshTime() }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshBattery(i) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            context.registerReceiver(
                timeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
            )
        }
        runCatching {
            val battery = context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            if (battery != null) refreshBattery(battery)
        }
        refreshTime()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(timeReceiver) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun refreshTime() {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        timeView.text = SimpleDateFormat(pattern, Locale.US).format(Date())
    }

    private fun refreshBattery(intent: Intent) {
        val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        batteryView.text = if (pct >= 0) {
            if (charging) "${pct}% ⚡" else "${pct}%"
        } else "—"
    }
}
