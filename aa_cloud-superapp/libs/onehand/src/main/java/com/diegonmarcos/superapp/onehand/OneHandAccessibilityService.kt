package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * Hosts the edge handles (WindowManager overlay), the live gesture-preview arrow,
 * AND the global-action dispatch. Being system-bound it needs no foreground
 * service / notification. One Hand Operation+ mechanic: swipe INWARD, the tilt
 * of the drag (diagonal) picks the direction; an arrow follows the finger and
 * shows the action about to fire; release triggers it. Hold = press + dwell.
 */
class OneHandAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private val views = mutableListOf<View>()
    private var preview: GesturePreviewView? = null
    private var cfg: OneHandConfig? = null

    private var downX = 0f
    private var downY = 0f
    private var activated = false
    private var pending: Runnable? = null

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (OneHandPrefs.isEnabled(this)) showHandles()
    }

    override fun onDestroy() {
        hideHandles(); if (instance === this) instance = null; super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    fun showHandles() {
        hideHandles()
        val c = OneHandConfig.effective(this).also { cfg = it }
        c.handles.forEach { addHandle(it) }
        addPreviewLayer() // on top (non-touchable → handle touches pass through)
    }

    fun hideHandles() {
        views.forEach { runCatching { windowManager?.removeView(it) } }
        views.clear()
        preview?.let { runCatching { windowManager?.removeView(it) } }
        preview = null
        cfg = null
    }

    private fun addPreviewLayer() {
        val wm = windowManager ?: return
        val v = GesturePreviewView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(v, lp)
        preview = v
    }

    private fun addHandle(h: OneHandConfig.Handle) {
        val wm = windowManager ?: return
        val dm = resources.displayMetrics
        val view = View(this).apply {
            setBackgroundColor(handleColor(h.transparency))
            setOnTouchListener { v, e -> onHandleTouch(h, v, e) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // Inset from the very border so the OS edge strip (Samsung/Termux system
        // gestures) stays free; our activation zone sits just inside it.
        val inset = dp(h.edgeInsetDp)
        when (h.edge) {
            OneHandConfig.Edge.BOTTOM -> {
                lp.width = dm.widthPixels * h.lengthPct / 100
                lp.height = dp(h.thicknessDp)
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.x = dm.widthPixels * h.positionPct / 100 - lp.width / 2
                lp.y = inset
            }
            else -> {
                lp.width = dp(h.thicknessDp)
                lp.height = dm.heightPixels * h.lengthPct / 100
                lp.gravity = (if (h.edge == OneHandConfig.Edge.LEFT) Gravity.START else Gravity.END) or
                    Gravity.TOP
                lp.x = inset
                lp.y = dm.heightPixels * h.positionPct / 100 - lp.height / 2
            }
        }
        wm.addView(view, lp)
        views.add(view)
        // No systemGestureExclusionRects on purpose: the handle sits ON the edge
        // and co-activates with the Samsung/Android/Termux edge gestures.
    }

    private fun onHandleTouch(h: OneHandConfig.Handle, view: View, e: MotionEvent): Boolean {
        val c = cfg ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; activated = false
                if (c.trigger == OneHandConfig.Trigger.TOUCH) activate(h)
                else {
                    val r = Runnable {
                        activate(h)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    pending = r; view.postDelayed(r, c.longPressMs.toLong())
                }
            }
            MotionEvent.ACTION_MOVE ->
                if (activated) updatePreview(h, e.rawX, e.rawY)
                // Moved before the long-press fired → a scroll/quick swipe, not our
                // gesture: cancel activation and let it pass as a normal swipe.
                else if (hypot(e.rawX - downX, e.rawY - downY) > dp(16)) cancelPending(view)
            MotionEvent.ACTION_UP -> {
                cancelPending(view)
                if (activated) {
                    SwipeClassifier.classify(h.edge, e.rawX - downX, e.rawY - downY, dp(c.swipeThresholdDp))
                        ?.let { key -> h.gestures[key]?.perform(this) }
                    preview?.end()
                }
                activated = false
            }
            MotionEvent.ACTION_CANCEL -> { cancelPending(view); preview?.end(); activated = false }
        }
        return true
    }

    private fun activate(h: OneHandConfig.Handle) {
        activated = true
        preview?.begin(buildOptions(h))
        preview?.update(downX, downY, downX, downY, null)
    }

    private fun cancelPending(view: View) {
        pending?.let { view.removeCallbacks(it) }; pending = null
    }

    private fun updatePreview(h: OneHandConfig.Handle, x: Float, y: Float) {
        preview?.update(downX, downY, x, y, SwipeClassifier.sector(h.edge, x - downX, y - downY))
    }

    /** Fan of the handle's 3 sector options for the preview, at canonical angles. */
    private fun buildOptions(h: OneHandConfig.Handle): List<GesturePreviewView.Option> =
        OneHandConfig.slotsFor(h.edge).map { slot ->
            val label = h.gestures[slot.key]?.let { labelForAction(it) } ?: slot.label
            GesturePreviewView.Option(slot.key, label, canonicalAngle(h.edge, slot.key))
        }

    private fun canonicalAngle(edge: OneHandConfig.Edge, key: String): Double = when (edge) {
        OneHandConfig.Edge.RIGHT -> when (key) { "top" -> -135.0; "down" -> 135.0; else -> 180.0 }
        OneHandConfig.Edge.LEFT -> when (key) { "top" -> -45.0; "down" -> 45.0; else -> 0.0 }
        OneHandConfig.Edge.BOTTOM -> when (key) { "left" -> -135.0; "right" -> -45.0; else -> -90.0 }
    }

    private fun labelForAction(action: GestureAction): String = when (action) {
        is GestureAction.Global ->
            action.action.name.lowercase().split('_')
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        is GestureAction.OpenApp ->
            cfg?.apps?.firstOrNull { it.pkg == action.pkg }?.label ?: action.pkg
    }

    private fun handleColor(transparency: Int): Int =
        if (transparency <= 0) Color.TRANSPARENT
        else Color.argb(transparency * 255 / 100, 255, 0, 0)

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        @Volatile var instance: OneHandAccessibilityService? = null
            private set
        val isConnected: Boolean get() = instance != null
    }
}
