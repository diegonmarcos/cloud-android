package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.graphics.PixelFormat
import android.util.Log
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
        val c = OneHandConfig.effective(this)
        Log.i(TAG, "onServiceConnected: enabled=${OneHandPrefs.isEnabled(this)} radial=${c.radial.enabled} sdk=${android.os.Build.VERSION.SDK_INT}")
        // Observe-only global touch for the two-finger radial menu (Android 14+).
        if (c.radial.enabled && android.os.Build.VERSION.SDK_INT >= 34) {
            runCatching {
                serviceInfo = serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                    setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN)
                }
                Log.i(TAG, "radial: FLAG_SEND_MOTION_EVENTS enabled")
            }.onFailure { Log.i(TAG, "radial: motion-events setup failed: ${it.message}") }
        }
        if (OneHandPrefs.isEnabled(this)) showHandles()
    }

    // ── Two-finger radial menu (observe-only via onMotionEvent) ──────────
    private val handler = Handler(Looper.getMainLooper())
    private var radialView: RadialMenuView? = null
    private var radialOpen = false
    private var radialPending: Runnable? = null
    private var rcx = 0f; private var rcy = 0f
    private var radialActive = -1

    override fun onMotionEvent(e: android.view.MotionEvent) {
        val c = cfg ?: OneHandConfig.effective(this).also { cfg = it }
        if (!c.radial.enabled) return
        val n = e.pointerCount
        if (!radialOpen) {
            if (n == 2) {
                if (radialPending == null) {
                    rcx = (e.getRawX(0) + e.getRawX(1)) / 2f
                    rcy = (e.getRawY(0) + e.getRawY(1)) / 2f
                    val r = Runnable { openRadial(c) }
                    radialPending = r; handler.postDelayed(r, c.radial.holdMs.toLong())
                } else {
                    val mx = (e.getRawX(0) + e.getRawX(1)) / 2f
                    val my = (e.getRawY(0) + e.getRawY(1)) / 2f
                    if (hypot(mx - rcx, my - rcy) > dp(c.radial.slopDp)) cancelRadialPending()
                }
            } else cancelRadialPending()
        } else {
            radialActive = radialView?.move(e.getRawX(0), e.getRawY(0), dp(72).toFloat()) ?: -1
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_UP -> fireRadial(c)   // last finger lifted
                android.view.MotionEvent.ACTION_CANCEL -> closeRadial()
            }
        }
    }

    private fun cancelRadialPending() {
        radialPending?.let { handler.removeCallbacks(it) }; radialPending = null
    }

    private fun openRadial(c: OneHandConfig) {
        radialPending = null
        val wm = windowManager ?: return
        val its = c.radial.items.map {
            RadialMenuView.Item(labelForAction(it), (it as? GestureAction.OpenApp)?.let { a -> appIcon(a.pkg) })
        }
        val v = RadialMenuView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(v, lp) }
        radialView = v; radialOpen = true; radialActive = -1
        v.open(rcx, rcy, dp(c.radial.radiusDp).toFloat(), its)
        Log.i(TAG, "radial OPEN at ${rcx.toInt()},${rcy.toInt()} items=${its.size}")
    }

    private fun fireRadial(c: OneHandConfig) {
        val idx = radialActive
        Log.i(TAG, "radial FIRE idx=$idx")
        c.radial.items.getOrNull(idx)?.perform(this)
        closeRadial()
    }

    private fun closeRadial() {
        radialView?.let { it.close(); runCatching { windowManager?.removeView(it) } }
        radialView = null; radialOpen = false; radialActive = -1
        cancelRadialPending()
    }

    override fun onDestroy() {
        hideHandles(); if (instance === this) instance = null; super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    fun showHandles() {
        hideHandles()
        val c = OneHandConfig.effective(this).also { cfg = it }
        Log.i(TAG, "showHandles: ${c.handles.size} handles, trigger=${c.trigger}, longPressMs=${c.longPressMs}")
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
            background = handleBackground(h.transparency)
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
        // GUARANTEE no back-gesture conflict: in gesture-nav mode Samsung's back
        // swipe fires only from the very edge strip, so we sit our handle just
        // PAST that strip (the safe zone). In 3-button mode there's no edge
        // gesture, so we hug the very edge (works perfectly there).
        val gestureNav = isGestureNav()
        val inset = if (gestureNav) dp(cfg?.edgeInsetGestureDp ?: 28) else dp(h.edgeInsetDp)
        Log.i(TAG, "addHandle ${h.id} gestureNav=$gestureNav inset=$inset")
        when (h.edge) {
            OneHandConfig.Edge.BOTTOM -> {
                lp.width = if (h.lengthDp > 0) dp(h.lengthDp) else dm.widthPixels * h.lengthPct / 100
                lp.height = dp(h.thicknessDp)
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.x = dm.widthPixels * h.positionPct / 100 - lp.width / 2
                lp.y = inset
            }
            else -> {
                lp.width = dp(h.thicknessDp)
                lp.height = if (h.lengthDp > 0) dp(h.lengthDp) else dm.heightPixels * h.lengthPct / 100
                lp.gravity = (if (h.edge == OneHandConfig.Edge.LEFT) Gravity.START else Gravity.END) or
                    Gravity.TOP
                lp.x = inset
                lp.y = dm.heightPixels * h.positionPct / 100 - lp.height / 2
            }
        }
        wm.addView(view, lp)
        views.add(view)
        Log.i(TAG, "addHandle ${h.id} edge=${h.edge} size=${lp.width}x${lp.height} x=${lp.x} y=${lp.y} inset=$inset")
        // CRITICAL for the LEFT handle: our inward swipe there IS the system
        // back-gesture direction, so without exclusion the OS steals it. The rect
        // MUST be set from a real layout pass (view.post fires while width/height
        // are still 0 → an EMPTY rect → no protection → the flaky, left-biased
        // failure). Re-apply on every layout so it's always a valid, non-empty rect.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val w = r - l; val hgt = b - t
                if (w > 0 && hgt > 0) {
                    v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, hgt))
                }
            }
        }
    }

    private fun onHandleTouch(h: OneHandConfig.Handle, view: View, e: MotionEvent): Boolean {
        val c = cfg ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; activated = false
                Log.i(TAG, "DOWN ${h.id} @${e.rawX.toInt()},${e.rawY.toInt()} trigger=${c.trigger}")
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
                else if (hypot(e.rawX - downX, e.rawY - downY) > dp(16)) {
                    Log.i(TAG, "MOVE-cancel ${h.id} (moved before long-press)")
                    cancelPending(view)
                }
            MotionEvent.ACTION_UP -> {
                cancelPending(view)
                if (activated) {
                    val key = SwipeClassifier.classify(h.edge, e.rawX - downX, e.rawY - downY, dp(c.swipeThresholdDp))
                    Log.i(TAG, "UP ${h.id} fire key=$key action=${key?.let { h.gestures[it] }}")
                    key?.let { h.gestures[it]?.perform(this) }
                    preview?.end()
                } else Log.i(TAG, "UP ${h.id} (not activated — nothing fired)")
                activated = false
            }
            MotionEvent.ACTION_CANCEL -> {
                Log.i(TAG, "CANCEL ${h.id} activated=$activated (OS likely claimed the gesture)")
                cancelPending(view); preview?.end(); activated = false
            }
        }
        return true
    }

    private fun activate(h: OneHandConfig.Handle) {
        activated = true
        Log.i(TAG, "activate ${h.id} — menu shown")
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
            val action = h.gestures[slot.key]
            val label = action?.let { labelForAction(it) } ?: slot.label
            val icon = (action as? GestureAction.OpenApp)?.let { appIcon(it.pkg) }
            GesturePreviewView.Option(slot.key, label, canonicalAngle(h.edge, slot.key), icon)
        }

    private fun appIcon(pkg: String): android.graphics.Bitmap? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val size = dp(48)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(android.graphics.Canvas(bmp)); bmp
    }.getOrNull()

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

    /** 0=3-button, 1=2-button, 2=gesture nav. Read from the framework resource. */
    private fun isGestureNav(): Boolean = runCatching {
        val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        id != 0 && resources.getInteger(id) == 2
    }.getOrDefault(false)

    // Visible rounded accent tab so the (otherwise invisible) touch zone is
    // findable. transparency 0..100 → alpha; a small non-zero default keeps it
    // subtle. Fully transparent (0) is still allowed for an invisible handle.
    private fun handleBackground(transparency: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb((transparency.coerceIn(0, 100)) * 255 / 100, 77, 163, 255))
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "OneHand"
        @Volatile var instance: OneHandAccessibilityService? = null
            private set
        val isConnected: Boolean get() = instance != null
    }
}
