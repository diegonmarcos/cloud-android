package com.diegonmarcos.superapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
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
    private val batteryView: BatteryIconView

    init {
        // OUTER strip is now VERTICAL — a horizontal row of (time +
        // battery) on top, and a 1dp hairline separator pinned to the
        // bottom edge so the user can clearly see where the launcher's
        // status bar ends and the home content begins.
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        // NO background — the parent FrameLayout's GalaxyBackdropView
        // already fills the camera/cutout + the rest of the screen
        // edge-to-edge. Transparent strip = galaxy reads continuous
        // from the punch-hole straight through. Text below uses a
        // strong shadow for contrast.
        setBackgroundColor(0x00000000)

        val px = (12 * resources.displayMetrics.density).toInt()
        val innerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px, 0, px, 0)
            // weight 1 → fills the available strip height MINUS the
            // 1dp separator beneath, so the strip's total height
            // (set by MainActivity.applyLauncherChrome to
            // statusBarHeightPx) is honoured without overflow.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        timeView = makeStripText().apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        batteryView = BatteryIconView(context).apply {
            // BatteryIconView reports its intrinsic size via onMeasure
            // (~36dp × 15dp). LayoutParams.WRAP_CONTENT honours that.
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            )
        }
        innerRow.addView(timeView)
        innerRow.addView(batteryView)
        addView(innerRow)

        // Bottom hairline — faint white (20% alpha) so it survives
        // both dark and bright patches of the galaxy backdrop.
        addView(View(context).apply {
            setBackgroundColor(0x33FFFFFF.toInt())
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                maxOf(1, (resources.displayMetrics.density * 0.75f).toInt()),
            )
        })
    }

    /** Shared style for the two strip TextViews — bold monospace
     *  white with a soft black halo shadow so the readout survives
     *  bright stars / nebula bands behind it. */
    private fun makeStripText(): TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        // dx=0, dy=1, radius=4, color=#CC000000 — drops a tight
        // anti-aliased halo so white text reads on any galaxy patch.
        setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
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
        // dd-MM-yyyy HH:mm EEE — 24h forced (Date pattern leaves no
        // ambiguity with locale 12h defaults), dash dates, 3-letter
        // weekday (Mon · Tue · Wed · Thu · Fri · Sat · Sun).
        timeView.text = SimpleDateFormat("dd-MM-yyyy HH:mm EEE", Locale.US).format(Date())
    }

    private fun refreshBattery(intent: Intent) {
        val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        batteryView.setBattery(pct, charging)
    }
}
