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
 * Foreground service that draws one handle per configured edge (One Hand
 * Operation+ style) and turns swipes into [OneHandAction]s via the accessibility
 * service. All geometry + gesture→action mapping is data-driven ([OneHandConfig]).
 */
class EdgeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val views = mutableListOf<View>()
    private val cfg by lazy { OneHandConfig.decode(BuildConfig.ONEHAND_CONFIG_B64) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cfg.handles.forEach { addHandle(it) }
    }

    private fun addHandle(h: OneHandConfig.Handle) {
        val dm = resources.displayMetrics
        val gesture = GestureDetector(this, HandleListener(h))
        val view = View(this).apply {
            setBackgroundColor(handleColor(h.transparency))
            setOnTouchListener { _, e -> gesture.onTouchEvent(e); true }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        when (h.edge) {
            OneHandConfig.Edge.BOTTOM -> {
                lp.width = dm.widthPixels * h.lengthPct / 100
                lp.height = dp(h.thicknessDp)
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.x = dm.widthPixels * h.positionPct / 100 - lp.width / 2
            }
            else -> {
                lp.width = dp(h.thicknessDp)
                lp.height = dm.heightPixels * h.lengthPct / 100
                lp.gravity = (if (h.edge == OneHandConfig.Edge.LEFT) Gravity.START else Gravity.END) or
                    Gravity.TOP
                lp.y = dm.heightPixels * h.positionPct / 100 - lp.height / 2
            }
        }
        windowManager.addView(view, lp)
        views.add(view)
    }

    private fun handleColor(transparency: Int): Int =
        if (transparency <= 0) Color.TRANSPARENT
        else Color.argb(transparency * 255 / 100, 255, 0, 0) // debug tint

    override fun onDestroy() {
        views.forEach { runCatching { windowManager.removeView(it) } }
        views.clear()
        super.onDestroy()
    }

    private inner class HandleListener(val h: OneHandConfig.Handle) :
        GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent) = true // claim the stream

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
            if (e1 == null) return false
            val key = SwipeClassifier.classify(
                h.edge, e2.x - e1.x, e2.y - e1.y, vX, vY,
                dp(cfg.swipeThresholdDp), dp(cfg.longSwipeDp), cfg.velocityThreshold,
            ) ?: return false
            fire(h.gestures[key])
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // ponytail: One Hand+'s "swipe-and-hold" is approximated by a
            // stationary long-press mapped to the handle's inward hold slot
            // (hold_in for side edges, hold_up for bottom). Full swipe-then-hold
            // directional detection is the upgrade path if needed.
            val holdKey = if (h.edge == OneHandConfig.Edge.BOTTOM) "hold_up" else "hold_in"
            fire(h.gestures[holdKey])
        }
    }

    private fun fire(action: OneHandAction?) {
        action?.let { OneHandAccessibilityService.instance?.perform(it) }
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
