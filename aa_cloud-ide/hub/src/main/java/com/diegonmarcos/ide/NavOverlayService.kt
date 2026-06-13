package com.diegonmarcos.ide

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * THE wrapper chrome: a PERSISTENT top nav bar drawn as a system overlay,
 * floating across ALL our apps (SuperApp, the hub, Acode, Amaze) until ✕.
 *
 *     Cloud-SuperApp | Cloud-IDE │ * Acode | Amaze File Manager
 *
 * One chip per ancestor (contract.navigation.parent_chain via NavBar) and one
 * per self-contained app (ForkRegistry.homeForks) — fully data-driven, zero
 * hardcoded names. The CURRENT app's chip is marked (●, full opacity). Tapping
 * a chip switches apps and re-renders the marker; the bar STAYS (persistent
 * across apps — that's its job). A fork chip that isn't installed yet routes
 * back to the hub, where the tile installs it from the bundle.
 *
 * Needs the one-time "Display over other apps" grant; MainActivity routes the
 * user there. Without it the service no-ops (apps still launch).
 */
class NavOverlayService : Service() {

    private var bar: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { removeBar(); stopSelf(); return START_NOT_STICKY }
            else -> showBar(intent?.getStringExtra(EXTRA_CURRENT_PKG) ?: packageName)
        }
        return START_STICKY
    }

    private data class Entry(val name: String, val pkg: String, val isFork: Boolean)

    /** parent_chain (Cloud-SuperApp, Cloud-IDE) + the two embedded apps. */
    private fun entries(): List<Entry> =
        NavBar.ancestorsOf("").map { Entry(it.name, it.pkg, isFork = false) } +
            ForkRegistry.homeForks.map { Entry(it.displayName, it.launchPackage, isFork = true) }

    private fun showBar(currentPkg: String) {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        removeBar()

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = 14f * d
                setColor(0xEE111111.toInt())
                setStroke(maxOf(1, dp(1)), 0x44FFFFFF.toInt())
            }
        }

        var lastWasFork = false
        for (e in entries()) {
            if (e.isFork && !lastWasFork) row.addView(separator(::dp))   // parents │ apps
            lastWasFork = e.isFork
            val current = e.pkg == currentPkg
            row.addView(chip(if (current) "● ${e.name}" else e.name, current, ::dp) {
                switchTo(e)
            })
        }
        row.addView(chip("✕", false, ::dp) { hide(this) })

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
        }
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(scroller, lp)
        bar = scroller
    }

    /** Switch to [e]'s app and re-render the bar with the new current marker.
     *  Uninstalled fork → route to the hub (its tile installs from the bundle). */
    private fun switchTo(e: Entry) {
        val launch = packageManager.getLaunchIntentForPackage(e.pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launch)
            showBar(e.pkg)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(it)
            }
            showBar(packageName)
        }
    }

    private fun chip(label: String, current: Boolean, dp: (Int) -> Int, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(if (current) 0xFFFFFFFF.toInt() else 0xFFE9D8FD.toInt())
            alpha = if (current) 1f else 0.75f
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, if (current) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun separator(dp: (Int) -> Int): TextView =
        TextView(this).apply {
            text = "│"
            setTextColor(0x55FFFFFF.toInt())
            textSize = 12f
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }

    private fun removeBar() {
        bar?.let {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        bar = null
    }

    override fun onDestroy() { removeBar(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_HIDE = "com.diegonmarcos.ide.overlay.HIDE"
        private const val EXTRA_CURRENT_PKG = "current_pkg"

        /** Raise/refresh the persistent bar with [currentPkg] marked. */
        fun show(ctx: Context, currentPkg: String) {
            // Disabled by default — Cloud-SuperApp owns the system-wide bar.
            // Re-enabled via Configs (IdePrefs). All rendering code below stays.
            if (!IdePrefs.overlayNavBar(ctx)) return
            ctx.startService(Intent(ctx, NavOverlayService::class.java).apply {
                putExtra(EXTRA_CURRENT_PKG, currentPkg)
            })
        }

        fun hide(ctx: Context) {
            ctx.startService(Intent(ctx, NavOverlayService::class.java).apply { action = ACTION_HIDE })
        }

        fun hasPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        /** Route the user to the one-time "Display over other apps" grant. */
        fun requestPermission(ctx: Context) {
            ctx.startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
