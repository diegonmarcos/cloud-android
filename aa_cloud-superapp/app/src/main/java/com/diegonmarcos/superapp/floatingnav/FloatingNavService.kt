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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
    // Expanded view (album-art card + all 5 actions) vs the compact menu.
    private var fullView = false
    private var torchOn = false
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

    // ── The menu box (compact 2-line, or Expanded view) ────────────
    private fun showBar(ctx: NavContext) {
        removeBubble(); removeBar()
        expanded = true
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(0xEE, 0x16, 0x16, 0x1C))
                setStroke(dp(1), Color.argb(0x55, 0x7C, 0x3A, 0xED))
            }
            val p = dp(12); setPadding(p, dp(10), p, dp(10))
        }
        // Expanded view only: now-playing album-art card on top (mock data).
        if (fullView) col.addView(albumCard())
        // Line 1 — the three hubs; bold the current context's hub.
        col.addView(itemRow(cfg.parents, boldId = ctx.id, topGap = fullView))
        // Line 2 — the current context's children.
        col.addView(itemRow(ctx.children, boldId = null, topGap = true))
        // Action line(s): compact shows the first N; expanded shows all,
        // wrapped at 3 chips per row.
        val acts = if (fullView) cfg.actions else cfg.actions.take(cfg.compactActionCount)
        for (rowItems in acts.chunked(3)) col.addView(itemRow(rowItems, boldId = null, topGap = true))
        // Expand / collapse toggle.
        col.addView(expandToggle(ctx))

        runCatching { wm.addView(col, barParams(outsideTouch = true)) }
        col.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        bar = col
    }

    /** Toggle between the compact menu and the Expanded view (re-renders). */
    private fun expandToggle(ctx: NavContext): View = TextView(this).apply {
        text = if (fullView) "⤡  Less" else "⤢  Expanded view"
        setTextColor(0xFF9CC2FF.toInt()); textSize = 12f
        gravity = Gravity.CENTER
        isClickable = true
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.topMargin = dp(8); layoutParams = lp
        setPadding(dp(8), dp(6), dp(8), dp(2))
        setOnClickListener { fullView = !fullView; showBar(ctx) }
    }

    /** Mock now-playing card so the Expanded view is visualizable without a
     *  live media session (real MediaSession metadata replaces it later). */
    private fun albumCard(): View {
        val m = cfg.expandedMock
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
            }
            setPadding(dp(8), dp(8), dp(16), dp(8))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.bottomMargin = dp(2); layoutParams = lp
        }
        card.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(m.artTop, m.artBottom),
            ).apply { cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); lp.leftMargin = dp(12); layoutParams = lp
            addView(TextView(this@FloatingNavService).apply {
                text = "♪  ${m.title}"; setTextColor(0xFFFFFFFF.toInt()); textSize = 13.5f
                maxLines = 1; setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@FloatingNavService).apply {
                text = m.artist; setTextColor(0xBBFFFFFF.toInt()); textSize = 11f; maxLines = 1
            })
        })
        card.addView(chip("▶", bold = true) { /* mock — no transport yet */ })
        return card
    }

    /** One horizontal row of pipe-separated chips. `boldId` bolds the parent
     *  whose target maps to that context (self→default, app:<pkg-prefix>). */
    private fun itemRow(items: List<NavItem>, boldId: String?, topGap: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (topGap) setPadding(0, dp(6), 0, 0)
        }
        for ((i, item) in items.withIndex()) {
            if (i > 0) row.addView(divider())
            val bold = boldId != null && parentMatchesContext(item.target, boldId)
            row.addView(chip(item.label, bold = bold) { handleTarget(item); collapse() })
        }
        return row
    }

    private fun parentMatchesContext(target: String, ctxId: String): Boolean = when {
        target == "self" -> ctxId == "default"
        target.startsWith("app:") -> {
            val pkg = target.removePrefix("app:")
            (ctxId == "comms" && pkg.startsWith("com.diegonmarcos.comms")) ||
                (ctxId == "ide" && pkg.startsWith("com.diegonmarcos.ide"))
        }
        else -> false
    }

    private fun removeBar() { bar?.let { runCatching { wm.removeView(it) } }; bar = null }

    private fun collapse() {
        expanded = false
        forced = false
        fullView = false
        removeBar()
        if (lastForeground != packageName) showBubble()
    }

    // ── Item dispatch ──────────────────────────────────────────────
    /** Route a tapped item by its target scheme. */
    private fun handleTarget(item: NavItem) {
        when (val t = item.target) {
            "self" -> launchPackage(packageName)
            "torch" -> toggleTorch()
            "calc" -> openCalculator()
            "lock" -> runCatching { com.diegonmarcos.superapp.ScreenLocker.lock(this) }
            "screensaver" -> ScreensaverService.start(this)
            // section:/action:/page:/… → bring Cloud-SuperApp forward and let
            // its shortcut_action handler (onTileClicked) navigate.
            else -> if (t.startsWith("app:")) openApp(t.removePrefix("app:"), item.installApp)
            else openSuperApp(t)
        }
    }

    /** Toggle the flashlight — CameraManager.setTorchMode needs no permission. */
    private fun toggleTorch() {
        runCatching {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        }
    }

    /** Open the device calculator via the standard CATEGORY_APP_CALCULATOR. */
    private fun openCalculator() {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) != null) runCatching { startActivity(intent) }
        else android.widget.Toast.makeText(this, "No calculator app found", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openApp(pkg: String, installApp: String) {
        if (launchPackage(pkg)) return
        if (installApp.isNotBlank()) {
            val app = Sections.externalApp(installApp)
            if (app != null && app.installApkUrl.isNotBlank() && app.installPackage.isNotBlank()) {
                runCatching {
                    Updater.installApk(applicationContext, app.installApkUrl, app.installPackage, app.label)
                }
                return
            }
        }
        launchPackage(packageName) // fall back to SuperApp so it can be installed there
    }

    /** Bring Cloud-SuperApp forward carrying a shortcut_action so it navigates
     *  to the section/action/page (works cold or warm — see MainActivity). */
    private fun openSuperApp(shortcutAction: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("shortcut_action", shortcutAction)
        runCatching { startActivity(intent) }
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

    private fun barParams(outsideTouch: Boolean = false): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (outsideTouch) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        return WindowManager.LayoutParams(wrap, wrap, type, flags, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Push the box down from the top edge by the data-driven %.
            y = (resources.displayMetrics.heightPixels * cfg.verticalOffsetPct / 100)
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
            // Persistent: FLAG_NO_CLEAR blocks swipe-to-dismiss + "clear all";
            // FLAG_ONGOING_EVENT keeps it pinned while the service runs.
            .apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
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
