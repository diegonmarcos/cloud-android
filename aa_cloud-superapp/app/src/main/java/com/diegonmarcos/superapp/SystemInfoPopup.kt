package com.diegonmarcos.superapp

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * System info popup — shown when the user taps the RAM% or Storage%
 * icon in the right status-strip cluster. Mirrors the battery popup
 * layout: dark-glass bubble, monospace, anchored under the tapped
 * icon. Headline is the device model so the user can read it at a
 * glance.
 *
 * Rows:
 *   • Device   — manufacturer · model
 *   • Android  — version (SDK API)
 *   • App      — versionName · git sha · build timestamp (BuildConfig)
 *   • Memory   — used / total · free
 *   • Storage  — used / total · free (/data)
 *   • CPU      — cores · arch
 *   • Uptime   — process uptime (AppProcessUptime)
 */
object SystemInfoPopup {

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

        container.addView(label(ctx, "Device"))
        container.addView(valueBig(ctx, "${Build.MANUFACTURER} · ${Build.MODEL}"))
        container.addView(spacer(ctx, (10 * d).toInt()))

        container.addView(label(ctx, "Android"))
        container.addView(valueSmall(ctx, "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
        container.addView(spacer(ctx, (4 * d).toInt()))

        container.addView(label(ctx, "App"))
        container.addView(valueSmall(ctx,
            "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA}"))
        container.addView(valueSmall(ctx, BuildConfig.BUILD_TIMESTAMP))
        container.addView(spacer(ctx, (6 * d).toInt()))

        container.addView(label(ctx, "Memory"))
        container.addView(valueSmall(ctx, readMemory(ctx)))
        container.addView(spacer(ctx, (4 * d).toInt()))

        container.addView(label(ctx, "Storage"))
        container.addView(valueSmall(ctx, readStorage()))
        container.addView(spacer(ctx, (4 * d).toInt()))

        container.addView(label(ctx, "CPU"))
        container.addView(valueSmall(ctx,
            "${Runtime.getRuntime().availableProcessors()} cores · ${primaryAbi()}"))
        container.addView(spacer(ctx, (4 * d).toInt()))

        // CPU load — /proc/loadavg three windows (1m · 5m · 15m) +
        // /proc/uptime busy seconds. No perm needed. Hidden when the
        // kernel files aren't readable on this device (very rare).
        SysfsProc.cpuLoad()?.let { c ->
            container.addView(label(ctx, "CPU load  (1m · 5m · 15m)"))
            container.addView(valueSmall(ctx,
                "%.2f · %.2f · %.2f".format(c.loadAvg1m, c.loadAvg5m, c.loadAvg15m)))
            container.addView(spacer(ctx, (4 * d).toInt()))
            container.addView(label(ctx, "CPU busy / idle  (Σ ${c.cores} cores)"))
            container.addView(valueSmall(ctx,
                "busy %s · idle %s · %.1f %%".format(
                    fmtSecs(c.busyAggregateSec.toLong()),
                    fmtSecs(c.idleAggregateSec.toLong()),
                    c.busyFraction * 100.0,
                )))
            container.addView(spacer(ctx, (4 * d).toInt()))
        }

        container.addView(label(ctx, "Uptime"))
        container.addView(valueSmall(ctx, readUptime()))

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
        pw.showAsDropDown(anchor, 0, (6 * d).toInt(), Gravity.END)
    }

    private fun readMemory(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "—"
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val total = mi.totalMem
            val avail = mi.availMem
            val used = (total - avail).coerceAtLeast(0L)
            val pct = if (total > 0L) (used * 100 / total).toInt() else 0
            "${fmtBytes(used)} / ${fmtBytes(total)} · $pct% used · ${fmtBytes(avail)} free"
        } catch (_: Throwable) { "—" }
    }

    private fun readStorage(): String = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val avail = stat.availableBlocksLong * stat.blockSizeLong
        val used = (total - avail).coerceAtLeast(0L)
        val pct = if (total > 0L) (used * 100 / total).toInt() else 0
        "${fmtBytes(used)} / ${fmtBytes(total)} · $pct% used · ${fmtBytes(avail)} free"
    } catch (_: Throwable) { "—" }

    private fun primaryAbi(): String = try {
        Build.SUPPORTED_ABIS.firstOrNull() ?: "—"
    } catch (_: Throwable) { "—" }

    private fun readUptime(): String {
        // AppProcessUptime exposes startedAtElapsed (an absolute
        // elapsed-realtime anchor); compute the delta to "now" here.
        // SystemClock.elapsedRealtime ticks through Doze so the count
        // doesn't reset on screen-off.
        val ms = (SystemClock.elapsedRealtime() - AppProcessUptime.startedAtElapsed)
            .coerceAtLeast(0L)
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }

    private fun fmtSecs(s: Long): String {
        if (s <= 0L) return "0 s"
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d m".format(h, m)
            m > 0 -> "%d m %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L     -> "%.2f MB".format(b / 1_048_576.0)
        b >= 1024L          -> "%.1f kB".format(b / 1024.0)
        else                -> "$b B"
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
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private fun valueSmall(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}
