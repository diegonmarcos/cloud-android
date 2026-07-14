package com.diegonmarcos.superapp.onehand

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Foreground service that draws a thin, near-invisible handle on the configured
 * screen edge and turns swipes into [OneHandAction]s (dispatched through the
 * accessibility service). Config is data-driven — see [OneHandConfig].
 */
class EdgeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var edgeView: View? = null
    private val cfg by lazy { OneHandConfig.decode(BuildConfig.ONEHAND_CONFIG_B64) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addHandle()
    }

    private fun addHandle() {
        val gesture = GestureDetector(this, EdgeGestureListener())
        val view = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, e -> gesture.onTouchEvent(e); true }
        }
        val heightPx = (resources.displayMetrics.heightPixels * cfg.handleHeightPct / 100)
        val lp = WindowManager.LayoutParams(
            dp(cfg.handleWidthDp),
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (cfg.edge == OneHandConfig.Edge.LEFT) Gravity.START else Gravity.END) or
                Gravity.CENTER_VERTICAL
        }
        windowManager.addView(view, lp)
        edgeView = view
    }

    override fun onDestroy() {
        edgeView?.let { runCatching { windowManager.removeView(it) } }
        edgeView = null
        super.onDestroy()
    }

    private inner class EdgeGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true // claim the stream so onFling fires
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
            if (e1 == null) return false
            val key = SwipeClassifier.classify(
                cfg.edge, e2.x - e1.x, e2.y - e1.y, vX, vY,
                dp(cfg.swipeThresholdDp), cfg.velocityThreshold,
            ) ?: return false
            cfg.gestures[key]?.let { OneHandAccessibilityService.instance?.perform(it) }
            return true
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.onehand_notification_channel),
                    NotificationManager.IMPORTANCE_MIN),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentText(getString(R.string.onehand_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_more)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "onehand_edge"
        private const val NOTIF_ID = 4271

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, EdgeOverlayService::class.java))
        fun stop(ctx: Context) =
            ctx.stopService(Intent(ctx, EdgeOverlayService::class.java))
    }
}
