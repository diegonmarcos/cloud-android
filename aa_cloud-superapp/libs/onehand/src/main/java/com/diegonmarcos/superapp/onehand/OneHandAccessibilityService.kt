package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts BOTH the edge handles (WindowManager overlay) AND the global-action
 * dispatch. Being a persistent system-bound service it needs NO foreground
 * notification — so there's no notification-center entry for the feature.
 * The overlay is (re)built from [OneHandConfig.effective] whenever it's shown,
 * so per-swipe edits apply on next enable.
 */
class OneHandAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private val views = mutableListOf<View>()

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (OneHandPrefs.isEnabled(this)) showHandles()
    }

    override fun onDestroy() {
        hideHandles()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    fun showHandles() {
        hideHandles()
        val cfg = OneHandConfig.effective(this)
        cfg.handles.forEach { addHandle(it, cfg) }
    }

    fun hideHandles() {
        views.forEach { runCatching { windowManager?.removeView(it) } }
        views.clear()
    }

    private fun addHandle(h: OneHandConfig.Handle, cfg: OneHandConfig) {
        val wm = windowManager ?: return
        val dm = resources.displayMetrics
        val gesture = GestureDetector(this, HandleListener(h, cfg))
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
        wm.addView(view, lp)
        views.add(view)
    }

    private fun handleColor(transparency: Int): Int =
        if (transparency <= 0) Color.TRANSPARENT
        else Color.argb(transparency * 255 / 100, 255, 0, 0)

    private inner class HandleListener(val h: OneHandConfig.Handle, val cfg: OneHandConfig) :
        GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent) = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
            if (e1 == null) return false
            val key = SwipeClassifier.classify(
                h.edge, e2.x - e1.x, e2.y - e1.y, vX, vY,
                dp(cfg.swipeThresholdDp), dp(cfg.longSwipeDp), cfg.velocityThreshold,
            ) ?: return false
            h.gestures[key]?.perform(this@OneHandAccessibilityService)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // ponytail: swipe-and-hold approximated by a stationary long-press →
            // the handle's inward hold slot. Directional hold is the upgrade path.
            val holdKey = if (h.edge == OneHandConfig.Edge.BOTTOM) "hold_up" else "hold_in"
            h.gestures[holdKey]?.perform(this@OneHandAccessibilityService)
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        @Volatile var instance: OneHandAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
