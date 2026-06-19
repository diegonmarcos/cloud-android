package com.diegonmarcos.superapp.floatingnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screensaver — a full-screen OPAQUE BLACK overlay (SYSTEM_ALERT_WINDOW), NOT a
 * lock and NOT an Activity: the app underneath stays resumed, so a playing
 * video/audio keeps going, just hidden behind the black. Shows the time + date
 * at the top and a single "Unlock" button at the bottom (the only actionable).
 *
 * Behaviour matches the "Black Screen" app, renamed Screensaver. Triggered by
 * the floating-nav `screensaver` action. Formats are data-driven
 * (build.json::ui.screensaver → BuildConfig.SCREENSAVER_*).
 */
class ScreensaverService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var clock: TextView? = null
    private var dateLabel: TextView? = null

    private val timeFmt by lazy { SimpleDateFormat(BuildConfig.SCREENSAVER_TIME_FORMAT, Locale.getDefault()) }
    private val dateFmt by lazy { SimpleDateFormat(BuildConfig.SCREENSAVER_DATE_FORMAT, Locale.getDefault()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { wm.removeView(it) } }; overlay = null
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock?.text = runCatching { timeFmt.format(now) }.getOrDefault("")
            dateLabel?.text = runCatching { dateFmt.format(now) }.getOrDefault("")
            main.postDelayed(this, 1000L)
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        // Configs → Launcher → Screensaver picker. 'neon_lights' swaps the
        // black backdrop for an animated neon synthwave grid + neon-glow clock;
        // anything else = the classic black clock.
        val neon = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).screensaver == "neon_lights"
        }.getOrDefault(false)
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (neon) 0xFF0A0014.toInt() else Color.BLACK)
            isClickable = true; isFocusable = true   // swallow taps to the app underneath
        }
        // Neon scene sits behind everything (added first).
        if (neon) root.addView(NeonBackdropView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Time + date, near the top.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clock = TextView(this@ScreensaverService).apply {
                setTextColor(0xFFEDEDED.toInt()); textSize = 56f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
            dateLabel = TextView(this@ScreensaverService).apply {
                setTextColor(0xCCFFFFFF.toInt()); textSize = 18f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(clock); addView(dateLabel)
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(72) }
        })
        // Unlock button, near the bottom.
        root.addView(TextView(this).apply {
            text = BuildConfig.SCREENSAVER_UNLOCK_LABEL
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            gravity = Gravity.CENTER
            isAllCaps = false
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
                setStroke(dp(1), Color.argb(0x66, 0xFF, 0xFF, 0xFF))
            }
            setPadding(dp(40), dp(12), dp(40), dp(12))
            isClickable = true
            setOnClickListener { stopSelf() }
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(96) } // clear the system nav bar
        })

        if (neon) clock?.apply {
            setTextColor(0xFFEAFCFF.toInt())
            setShadowLayer(28f, 0f, 0f, 0xFF18E0FF.toInt())
        }

        runCatching { wm.addView(root, overlayParams()) }
        overlay = root
    }

    /** Animated neon "synthwave" grid — a scrolling horizontal floor below a
     *  horizon plus converging verticals, cyan + magenta with a glow. Software
     *  layer so the shadow-layer glow renders. Stops itself on detach. */
    private inner class NeonBackdropView(ctx: Context) : View(ctx) {
        private var t = 0f
        private val floor = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
            color = 0xFF18E0FF.toInt(); setShadowLayer(10f, 0f, 0f, 0xFF18E0FF.toInt())
        }
        private val rays = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
            color = 0xFFFF2EC4.toInt(); setShadowLayer(10f, 0f, 0f, 0xFFFF2EC4.toInt())
        }
        private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val horizon = h * 0.58f
            val rows = 16
            for (i in 0 until rows) {
                val p = ((i + t) % rows) / rows            // 0..1, scrolling toward viewer
                val y = horizon + (h - horizon) * (p * p)  // perspective: bunch at horizon
                canvas.drawLine(0f, y, w, y, floor)
            }
            val cols = 12
            for (i in 0..cols) {
                val x0 = w * i / cols
                val xv = w / 2f + (x0 - w / 2f) * 0.12f    // converge toward centre at horizon
                canvas.drawLine(x0, h, xv, horizon, rays)
            }
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        // Use the REAL screen size (incl. the system-bar regions) so the black
        // covers edge-to-edge — MATCH_PARENT gets clipped to the content frame,
        // leaving the app's purple gradient + nav bar area showing.
        val (w, h) = realScreenSize()
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, android.graphics.PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /** Full physical screen size, including the status- and nav-bar regions. */
    private fun realScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Screensaver", NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Active while the black-screen screensaver is up."; setShowBadge(false) })
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle("Cloud SuperApp — Screensaver")
            .setContentText("Tap Unlock on screen to dismiss.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "screensaver"
        private const val NOTIF_ID = 0xF2

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start the screensaver iff enabled and "display over other apps"
         *  is granted. Returns false otherwise (caller can prompt). */
        fun start(ctx: Context): Boolean {
            if (!BuildConfig.SCREENSAVER_ENABLED) return false
            if (!Settings.canDrawOverlays(ctx)) return false
            val i = Intent(ctx, ScreensaverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            return true
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreensaverService::class.java)) }
    }
}
