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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true; isFocusable = true   // swallow taps to the app underneath
        }
        // Time + date, near the top.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clock = TextView(this@ScreensaverService).apply {
                setTextColor(0xFFEDEDED.toInt()); textSize = 56f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
            dateLabel = TextView(this@ScreensaverService).apply {
                setTextColor(0x99FFFFFF.toInt()); textSize = 16f
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
                .apply { bottomMargin = dp(64) }
        })

        runCatching { wm.addView(root, overlayParams()) }
        overlay = root
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        return WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, android.graphics.PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.TOP or Gravity.START }
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
