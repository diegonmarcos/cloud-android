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
    private val media by lazy { MediaProxy(this) }
    private val infos by lazy { InfosNotifier(this) }
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: View? = null      // collapsed circle
    private var bar: View? = null         // expanded nav bar
    private var expanded = false
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
        runCatching { infos.refresh() } // grouped Infos notification (sample data)
        main.post(pollTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_MENU -> {
                // Explicit open (Sirius Star). Force the bar even though SuperApp
                // is foreground; default context applies here.
                forced = true
                main.post { cfg.contextFor(packageName)?.let { showBar(it) } }
            }
            ACTION_NAV -> {
                // A notification action button (Light/Screensaver/Calc/…) was
                // tapped — run it, no menu.
                intent.getStringExtra(EXTRA_TARGET)?.let { dispatch(it, "") }
            }
            ACTION_RENOTIFY -> {
                // User swiped the (supposedly persistent) notification away on
                // Android 14+ — re-post it immediately.
                main.post {
                    runCatching {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIF_ID, buildNotification())
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        removeBubble(); removeBar()
        runCatching { media.cancel() }
        runCatching { infos.cancel() }
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
            // Media-session notification tracks the active player independently
            // of the overlay (it only needs notification access).
            runCatching { media.refresh() }
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
        // We're in Cloud-SuperApp → NO bubble (the in-app trigger is the Sirius
        // Star). hostForeground is the reliable signal from MainActivity's
        // lifecycle; we also treat an UNKNOWN foreground (fg == null, e.g. fresh
        // install before UsageStats populates / without usage access) as "still
        // home" so the circle never wrongly appears over Cloud-SuperApp.
        if (hostForeground || fg == packageName || fg == null) {
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
        // Line 1 — the three hubs (centred, larger); bold the current context's hub.
        col.addView(itemRow(cfg.parents, boldId = ctx.id, size = 14f))
        // Line 2 — the current context's children (centred, one size smaller).
        // (Actions/album art live in the Android notification, not here.)
        col.addView(itemRow(ctx.children, boldId = null, topGap = true, size = 12f))

        runCatching { wm.addView(col, barParams(outsideTouch = true)) }
        col.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        bar = col
    }

    /** One horizontal row of pipe-separated chips. `boldId` bolds the parent
     *  whose target maps to that context (self→default, app:<pkg-prefix>). */
    private fun itemRow(items: List<NavItem>, boldId: String?, topGap: Boolean = false, size: Float = 13f): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER   // centre the chips within the full-width box
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (topGap) setPadding(0, dp(6), 0, 0)
        }
        for ((i, item) in items.withIndex()) {
            if (i > 0) row.addView(divider())
            val bold = boldId != null && parentMatchesContext(item.target, boldId)
            row.addView(chip(item.label, bold = bold, size = size) { handleTarget(item); collapse() })
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
        removeBar()
        if (lastForeground != packageName) showBubble()
    }

    // ── Item dispatch ──────────────────────────────────────────────
    /** Route a tapped nav item (overlay parents/children). */
    private fun handleTarget(item: NavItem) = dispatch(item.target, item.installApp)

    /** Shared target dispatch — used by the overlay nav AND the notification
     *  action buttons (Light/Screensaver/Calc/Lock/Search). */
    private fun dispatch(target: String, installApp: String) {
        when (target) {
            "self" -> launchPackage(packageName)
            "torch" -> toggleTorch()
            "calc" -> openCalculator()
            "lock" -> runCatching { com.diegonmarcos.superapp.ScreenLocker.lock(this) }
            "dnd" -> toggleDnd()
            "powersave" -> openPowerSaver()
            "screensaver" -> ScreensaverService.start(this)
            "infos:renotify" -> runCatching { infos.refresh() }
            else -> when {
                // Media transport (Prev/Play-Pause/Next) → active session.
                target.startsWith("media:") -> media.transport(target)
                target.startsWith("app:") -> openApp(target.removePrefix("app:"), installApp)
                // section:/action:/page:/… → bring Cloud-SuperApp forward and
                // let its shortcut_action handler (onTileClicked) navigate.
                else -> openSuperApp(target)
            }
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

    /** Toggle Do-Not-Disturb. Needs notification-policy access; if not granted,
     *  jump to the grant screen instead. */
    private fun toggleDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val dndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (dndOn) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
    }

    /** Battery saver can't be toggled programmatically (system-restricted) —
     *  open the battery-saver settings so the user flips it. */
    private fun openPowerSaver() {
        runCatching {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
    private fun chip(text: String, bold: Boolean = false, size: Float = 13f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (bold) 0xFFE9D8FD.toInt() else 0xFFFFFFFF.toInt())
            textSize = size
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
        val w = resources.displayMetrics.widthPixels * cfg.widthPct / 100
        return WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, type, flags,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Just below the dynamic island (data-driven dp from the top edge).
            y = dp(cfg.topOffsetDp)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Foreground-service notification (MediaStyle) ───────────────
    // Collapsed = the first `compact_action_count` (3) action buttons; expanded
    // = the album-art card (mock) + ALL action buttons (up to 5). Persistent
    // (FLAG_NO_CLEAR). Buttons + mock art are data-driven (ui.floating_nav).
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Floating nav bar", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Cloud SuperApp floating nav + quick actions."; setShowBadge(false) })
            }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle("Cloud SuperApp - NC Quick Actions")
            .setContentText("Quick actions")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            // Android 14+ lets the user swipe-dismiss even an ongoing FGS
            // notification; re-post it immediately when that happens so it
            // stays effectively un-removable.
            .setDeleteIntent(serviceAction(ACTION_RENOTIFY))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        val acts = cfg.actions.take(5)
        for (a in acts) b.addAction(actionIcon(a.target), a.label, actionPi(a.target))
        // Android shows max 3 buttons collapsed → pick the first N for compact.
        val compact = IntArray(minOf(cfg.compactActionCount, acts.size)) { it }
        b.setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(*compact))
        return b.build().apply {
            // Persistent: blocks swipe-to-dismiss + "clear all".
            flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        }
    }

    /** PendingIntent for a notification action — routed through the invisible
     *  NavActionActivity so tapping it ALSO closes the notification shade
     *  (a service PendingIntent would leave the shade open). */
    private fun actionPi(target: String): PendingIntent = PendingIntent.getActivity(
        this, target.hashCode(),
        Intent(this, NavActionActivity::class.java)
            .putExtra(NavActionActivity.EXTRA_TARGET, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** PendingIntent for a bare service action (e.g. re-notify on dismiss). */
    private fun serviceAction(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, FloatingNavService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Refined thin-line action icons (One-UI style), tinted by the system. */
    private fun actionIcon(target: String): Int = when (target) {
        "torch" -> R.drawable.ic_nav_torch
        "screensaver" -> R.drawable.ic_nav_screensaver
        "calc" -> R.drawable.ic_nav_calc
        "dnd" -> R.drawable.ic_nav_dnd
        "powersave" -> R.drawable.ic_nav_powersave
        "lock" -> R.drawable.ic_nav_lock
        else -> R.drawable.ic_nav_search
    }

    companion object {
        private const val CHANNEL_ID = "floating_nav"
        private const val NOTIF_ID = 0xF1
        const val ACTION_SHOW_MENU = "com.diegonmarcos.superapp.floatingnav.SHOW_MENU"
        internal const val ACTION_NAV = "com.diegonmarcos.superapp.floatingnav.NAV_ACTION"
        private const val ACTION_RENOTIFY = "com.diegonmarcos.superapp.floatingnav.RENOTIFY"
        internal const val EXTRA_TARGET = "target"

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

        /** True while Cloud-SuperApp (MainActivity) is in the foreground. The
         *  reliable signal for "we're home" — the floating circle must never
         *  show here (the in-app trigger is the Sirius Star). Set from
         *  MainActivity.onResume / onPause. */
        @Volatile
        var hostForeground: Boolean = false

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

        /** Run a notification-action target on the service (called by the
         *  NavActionActivity trampoline after it closes the shade). */
        fun runAction(ctx: Context, target: String) {
            val i = Intent(ctx, FloatingNavService::class.java)
                .setAction(ACTION_NAV).putExtra(EXTRA_TARGET, target)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
