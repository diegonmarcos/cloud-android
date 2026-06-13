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
    // When the menu was opened explicitly (Sirius Star on the home screen),
    // keep it shown even while Cloud-SuperApp is foreground; cleared on collapse.
    private var forced = false
    private var currentContextId: String? = null
    private var lastForeground: String? = null
    // Persisted bubble position (px). Int.MIN_VALUE = unset → default top-centre.
    private var bubbleX = Int.MIN_VALUE
    private var bubbleY = Int.MIN_VALUE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        main.post(pollTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_MENU) {
            // Explicit open (Sirius Star). Force the bar even though SuperApp is
            // foreground; default context (Cloud-Comms | Cloud-IDE) applies here.
            forced = true
            main.post { cfg.contextFor(packageName)?.let { showBar(it) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
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
        // We're back home in Cloud-SuperApp → no overlay (reset expand state
        // so the bubble reappears the next time the user leaves) — UNLESS the
        // menu was force-opened from the home-screen Sirius Star.
        if (fg == packageName) {
            if (forced) return
            removeBubble(); removeBar(); expanded = false; currentContextId = null; return
        }
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
        val params = bubbleParams(size)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0x70, 0x7C, 0x3A, 0xED)) // semi-transparent violet
                setStroke(dp(1), Color.argb(0x55, 0xFF, 0xFF, 0xFF))
            }
            alpha = 0.55f
            setOnTouchListener(bubbleTouch(params))
        }
        runCatching { wm.addView(v, params) }
        bubble = v
    }

    /**
     * Tap-and-hold to move, quick-tap to open. A long-press arms drag mode
     * (with haptic feedback); only then does a finger move reposition the
     * window. A plain tap (no hold, no move) expands the nav bar. The final
     * position is persisted so it survives re-shows / restarts.
     */
    private fun bubbleTouch(lp: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        return object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f
            private var startX = 0; private var startY = 0
            private var dragging = false

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        // Free-drag: as soon as the finger travels past touch-slop
                        // it's a move (no long-press wait). First haptic = nice cue.
                        if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                            dragging = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (dragging) {
                            lp.x = (startX + dx).toInt().coerceIn(0, maxBubbleX(view.width))
                            lp.y = (startY + dy).toInt().coerceIn(0, maxBubbleY(view.height))
                            runCatching { wm.updateViewLayout(view, lp) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            bubbleX = lp.x; bubbleY = lp.y; savePos()
                        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                            // No drag → a tap → open the menu.
                            view.performClick()
                            cfg.contextFor(lastForeground)?.let { showBar(it) }
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun maxBubbleX(w: Int) = (resources.displayMetrics.widthPixels - w).coerceAtLeast(0)
    private fun maxBubbleY(h: Int) = (resources.displayMetrics.heightPixels - h).coerceAtLeast(0)

    private fun removeBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null }

    /** Window params for the draggable bubble — gravity TOP|START so x/y are
     *  absolute; defaults to top-centre on first show, then the saved spot. */
    private fun bubbleParams(size: Int): WindowManager.LayoutParams {
        if (bubbleX == Int.MIN_VALUE) loadPos()
        val defX = ((resources.displayMetrics.widthPixels - size) / 2).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bubbleX >= 0) bubbleX else defX
            y = if (bubbleY >= 0) bubbleY else dp(6)
        }
    }

    private fun posPrefs() = getSharedPreferences("floating_nav_pos", Context.MODE_PRIVATE)
    private fun loadPos() {
        bubbleX = posPrefs().getInt("x", -1)
        bubbleY = posPrefs().getInt("y", -1)
    }
    private fun savePos() {
        posPrefs().edit().putInt("x", bubbleX).putInt("y", bubbleY).apply()
    }

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
        forced = false
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
        const val ACTION_SHOW_MENU = "com.diegonmarcos.superapp.floatingnav.SHOW_MENU"

        /** Force-open the nav menu (the Sirius Star on the home screen). Starts
         *  the service if needed and force-expands the bar even while SuperApp
         *  is foreground. Returns false if "display over other apps" isn't
         *  granted (caller should prompt). */
        fun showMenu(ctx: Context): Boolean {
            if (!Settings.canDrawOverlays(ctx)) return false
            val i = Intent(ctx, FloatingNavService::class.java).setAction(ACTION_SHOW_MENU)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            return true
        }

        /** True while the overlay service is alive — drives the single
         *  Start/Stop toggle in Configs → Permissions. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start the overlay iff enabled in build.json AND the user has
         *  granted "display over other apps". Returns whether it started
         *  (false = feature disabled or overlay permission missing). */
        fun startIfPermitted(ctx: Context): Boolean {
            if (!FloatingNavConfig.get().enabled) return false
            if (!Settings.canDrawOverlays(ctx)) return false
            val i = Intent(ctx, FloatingNavService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            return true
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingNavService::class.java))
        }
    }
}
