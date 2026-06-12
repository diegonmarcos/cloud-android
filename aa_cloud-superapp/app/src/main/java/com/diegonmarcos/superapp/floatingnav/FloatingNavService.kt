package com.diegonmarcos.superapp.floatingnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.Sections
import com.diegonmarcos.superapp.updater.Updater

/**
 * Floating Top Nav Bar — a SYSTEM_ALERT_WINDOW overlay shown whenever the
 * user has left Cloud-SuperApp. Collapsed it's a small transparent circle at
 * top-centre; tapped it expands into a context-aware top nav bar.
 *
 * Context is data-driven ([FloatingNavConfig]): a poll loop reads the
 * foreground package via [UsageStatsManager] every `poll_ms` and picks the
 * matching [NavContext] (Cloud-Comms → Fossy | Element | Mattermost |
 * FairMail; Cloud-IDE → Acode | Amaze File; otherwise the default
 * Cloud-Comms | Cloud-IDE). When the foreground IS Cloud-SuperApp the overlay
 * is hidden entirely — we're already home.
 *
 * Self-managing foreground service (START_STICKY) so Android keeps it alive
 * while the user roams other apps. Gated by [Settings.canDrawOverlays] +
 * build.json::ui.floating_nav.enabled; started/stopped via the companion.
 */
class FloatingNavService : Service() {

    private val cfg by lazy { FloatingNavConfig.get() }
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: View? = null      // collapsed circle
    private var bar: View? = null         // expanded nav bar
    private var expanded = false
    private var currentContextId: String? = null
    private var lastForeground: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        main.post(pollTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        removeBubble(); removeBar()
        super.onDestroy()
    }

    // ── Poll loop ──────────────────────────────────────────────────
    private val pollTick = object : Runnable {
        override fun run() {
            if (!cfg.enabled || !Settings.canDrawOverlays(this@FloatingNavService)) {
                removeBubble(); removeBar()
            } else {
                refresh(foregroundPackage())
            }
            main.postDelayed(this, cfg.pollMs)
        }
    }

    /** Last package moved to foreground in the recent window (UsageStats). */
    private fun foregroundPackage(): String? = runCatching {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5_000, now)
        val e = UsageEvents.Event()
        var pkg: String? = lastForeground
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
        }
        lastForeground = pkg
        pkg
    }.getOrNull()

    /** Show / hide / re-skin the overlay for the current foreground app. */
    private fun refresh(fg: String?) {
        // We're back home in Cloud-SuperApp → no overlay.
        if (fg == packageName) { removeBubble(); removeBar(); currentContextId = null; return }
        val ctx = cfg.contextFor(fg) ?: run { removeBubble(); removeBar(); return }
        if (ctx.id != currentContextId) {
            currentContextId = ctx.id
            if (expanded) showBar(ctx) // re-skin live if already open
        }
        if (!expanded && bubble == null) showBubble()
    }

    // ── Collapsed circle ───────────────────────────────────────────
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(34)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0x70, 0x7C, 0x3A, 0xED)) // semi-transparent violet
                setStroke(dp(1), Color.argb(0x55, 0xFF, 0xFF, 0xFF))
            }
            alpha = 0.55f
            setOnClickListener {
                cfg.contextFor(lastForeground)?.let { showBar(it) }
            }
        }
        runCatching { wm.addView(v, barParams(size, size)) }
        bubble = v
    }

    private fun removeBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null }

    // ── Expanded nav bar ───────────────────────────────────────────
    private fun showBar(ctx: NavContext) {
        removeBubble(); removeBar()
        expanded = true
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(0xEE, 0x16, 0x16, 0x1C))
                setStroke(dp(1), Color.argb(0x55, 0x7C, 0x3A, 0xED))
            }
            val p = dp(10); setPadding(p + dp(4), dp(6), p + dp(4), dp(6))
        }
        // ✲ home chip — brings the context's hub (or Cloud-SuperApp) to front.
        row.addView(chip("✲ ${ctx.homeLabel}", bold = true) { goHome(ctx) })
        for (link in ctx.links) {
            row.addView(divider())
            row.addView(chip(link.label) { openLink(link); collapse() })
        }
        // collapse on a tap outside the bar.
        runCatching { wm.addView(row, barParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, outsideTouch = true)) }
        row.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        bar = row
    }

    private fun removeBar() { bar?.let { runCatching { wm.removeView(it) } }; bar = null }

    private fun collapse() {
        expanded = false
        removeBar()
        if (lastForeground != packageName) showBubble()
    }

    // ── Link actions ───────────────────────────────────────────────
    private fun goHome(ctx: NavContext) {
        val target = if (ctx.homePackage == "self") packageName else ctx.homePackage
        launchPackage(target)
        collapse()
    }

    private fun openLink(link: NavLink) {
        if (launchPackage(link.pkg)) return
        // Not installed → install the companion APK if the link declares one.
        if (link.installApp.isNotBlank()) {
            val app = Sections.externalApp(link.installApp)
            if (app != null && app.installApkUrl.isNotBlank() && app.installPackage.isNotBlank()) {
                runCatching {
                    Updater.installApk(applicationContext, app.installApkUrl, app.installPackage, app.label)
                }
                return
            }
        }
        // Otherwise bring the user home so they can install it from the grid.
        launchPackage(packageName)
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    // ── View helpers ───────────────────────────────────────────────
    private fun chip(text: String, bold: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (bold) 0xFFE9D8FD.toInt() else 0xFFFFFFFF.toInt())
            textSize = 13f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun divider(): View = TextView(this).apply {
        text = "|"; setTextColor(0x55FFFFFF.toInt()); textSize = 13f
        setPadding(dp(2), 0, dp(2), 0)
    }

    private fun barParams(w: Int, h: Int, outsideTouch: Boolean = false): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (outsideTouch) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return WindowManager.LayoutParams(w, h, type, flags, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Foreground-service notification ────────────────────────────
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Floating nav bar", NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Keeps the floating cross-app nav bar alive."; setShowBadge(false) })
            }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Cloud SuperApp — Floating nav")
            .setContentText("Tap the top-centre bubble to switch apps.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "floating_nav"
        private const val NOTIF_ID = 0xF1

        /** Start the overlay iff enabled in build.json AND the user has
         *  granted "display over other apps". No-op otherwise. */
        fun startIfPermitted(ctx: Context) {
            if (!FloatingNavConfig.get().enabled) return
            if (!Settings.canDrawOverlays(ctx)) return
            val i = Intent(ctx, FloatingNavService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingNavService::class.java))
        }
    }
}
