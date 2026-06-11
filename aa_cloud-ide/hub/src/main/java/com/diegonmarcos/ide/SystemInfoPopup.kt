package com.diegonmarcos.ide

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
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * About — the dark-glass system-info popup, ported equal to ea_cloud-superapp's
 * SystemInfoPopup (same dark bubble, monospace, same rows) and extended with the
 * Cloud-IDE-specific rows (IPC contract, forks, workspace). Anchored under the
 * tapped row like SuperApp's.
 *
 * Rows: Device · Android · App (versionName · sha · build-time) · Memory ·
 * Storage · CPU · CPU load (/proc/loadavg) · Uptime · IPC · Forks · Workspace.
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
        container.addView(valueSmall(ctx, "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA}"))
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

        readLoadAvg()?.let { la ->
            container.addView(label(ctx, "CPU load  (1m · 5m · 15m)"))
            container.addView(valueSmall(ctx, la))
            container.addView(spacer(ctx, (4 * d).toInt()))
        }

        // CPU busy/idle aggregate — /proc/uptime (uptime · idle-across-cores),
        // same row Cloud-SuperApp's About shows via SysfsProc.cpuLoad().
        readCpuBusyIdle()?.let { bi ->
            container.addView(label(ctx, "CPU busy / idle  (Σ ${Runtime.getRuntime().availableProcessors()} cores)"))
            container.addView(valueSmall(ctx, bi))
            container.addView(spacer(ctx, (4 * d).toInt()))
        }

        container.addView(label(ctx, "Uptime"))
        container.addView(valueSmall(ctx, readUptime()))
        container.addView(spacer(ctx, (10 * d).toInt()))

        // ── Cloud-IDE specifics ─────────────────────────────────────────
        container.addView(label(ctx, "IPC contract"))
        container.addView(valueSmall(ctx, "v${BuildConfig.IPC_VERSION} · ${BuildConfig.IPC_AUTHORITY}"))
        container.addView(valueSmall(ctx, "${BuildConfig.IPC_PERMISSION} (signature)"))
        container.addView(spacer(ctx, (4 * d).toInt()))

        container.addView(label(ctx, "Forks"))
        for (f in ForkRegistry.forks) {
            val state = when {
                f.blockedOn != null -> "blocked"
                f.isInstalled(ctx) -> "installed"
                else -> "not installed"
            }
            container.addView(valueSmall(ctx,
                "${f.domain} · ${f.license} · pin ${f.pinnedTag.ifEmpty { "—" }} · $state"))
        }
        container.addView(spacer(ctx, (4 * d).toInt()))

        container.addView(label(ctx, "Workspace"))
        container.addView(valueSmall(ctx, Endpoints.workspaceRoot() ?: "—"))
        container.addView(valueSmall(ctx, "update tag: ${BuildConfig.AUTO_UPDATE_TAG} · ${BuildConfig.GHCR_REGISTRY}/${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}"))

        val scroller = ScrollView(ctx).apply { addView(container) }

        val pw = PopupWindow(
            scroller,
            (300 * d).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8 * d
            height = (ctx.resources.displayMetrics.heightPixels * 0.7f).toInt()
        }
        pw.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    private fun readMemory(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "—"
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val total = mi.totalMem; val avail = mi.availMem
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

    private fun primaryAbi(): String = try { Build.SUPPORTED_ABIS.firstOrNull() ?: "—" } catch (_: Throwable) { "—" }

    /** Minimal /proc/loadavg read (no extra deps). "1.20 · 0.80 · 0.50" or null. */
    private fun readLoadAvg(): String? = try {
        val parts = File("/proc/loadavg").readText().trim().split(' ')
        if (parts.size >= 3) "${parts[0]} · ${parts[1]} · ${parts[2]}" else null
    } catch (_: Throwable) { null }

    /** /proc/uptime → "busy 3 h 12 m · idle 21 h 04 m · 13.2 %" (idle is already
     *  core-aggregated; busy = uptime×cores − idle). Mirrors SuperApp's row. */
    private fun readCpuBusyIdle(): String? {
        return try {
            val parts = File("/proc/uptime").readText().trim().split(' ').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val uptime = parts[0].toDouble()
            val idle = parts[1].toDouble()
            val cores = Runtime.getRuntime().availableProcessors()
            val busy = (uptime * cores - idle).coerceAtLeast(0.0)
            val frac = if (busy + idle > 0) busy / (busy + idle) else 0.0
            "busy ${fmtSecs(busy.toLong())} · idle ${fmtSecs(idle.toLong())} · %.1f %%".format(frac * 100.0)
        } catch (_: Throwable) { null }
    }

    private fun fmtSecs(s: Long): String {
        if (s <= 0L) return "0 s"
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            h > 0 -> "%d h %02d m".format(h, m)
            m > 0 -> "%d m %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }

    private fun readUptime(): String {
        val ms = (SystemClock.elapsedRealtime() - AppProcessUptime.startedAtElapsed).coerceAtLeast(0L)
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d s".format(m, sec)
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
        text = t; setTextColor(0xAAFFFFFFL.toInt()); textSize = 11f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun valueBig(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; setTextColor(0xFFFFFFFFL.toInt()); textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private fun valueSmall(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; setTextColor(0xFFFFFFFFL.toInt()); textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}
