package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
    private var replaying = false // guard: ignore our own injected tap

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "onServiceConnected: enabled=${OneHandPrefs.isEnabled(this)} sdk=${android.os.Build.VERSION.SDK_INT}")
        // NEVER request global motion events (FLAG_SEND_MOTION_EVENTS): on some
        // devices that INTERCEPTS the whole touchscreen and freezes all input.
        // We only ever add a small touchable handle window, and only when the
        // user has explicitly enabled the feature (default OFF → not auto-active).
        if (OneHandPrefs.isEnabled(this)) showHandles()
    }

    // ── Two-finger radial menu (observe-only via onMotionEvent) ──────────
    private val handler = Handler(Looper.getMainLooper())
    private var radialView: RadialMenuView? = null
    private var radialOpen = false
    private var radialPending: Runnable? = null
    private var rcx = 0f; private var rcy = 0f
    private var radialActive = -1

    // ── Observe-only edge handle (API 34+): non-touchable window never holds a
    //    touch; long-press detected purely from onMotionEvent, so short taps &
    //    everything else pass straight to the app. ──
    // HARD-DISABLED: observe-only relied on FLAG_SEND_MOTION_EVENTS which
    // intercepted the whole touchscreen and froze all input on real devices.
    // Never re-enable without a per-app allowlisted, verified-safe path.
    private val observeMode = false
    private val handleRects = mutableListOf<Pair<OneHandConfig.Handle, android.graphics.Rect>>()
    private var activeHandle: OneHandConfig.Handle? = null
    private var handlePending: Runnable? = null

    // NOTE: onMotionEvent()/observe-only DELETED on purpose — that path required
    // FLAG_SEND_MOTION_EVENTS which intercepted the whole touchscreen and froze
    // input. The handle is a plain touchable window (only its own band); short
    // taps are forwarded to the app via replayTap. No global touch handling.

    private fun cancelHandlePending() {
        handlePending?.let { handler.removeCallbacks(it) }; handlePending = null
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
        lp.alpha = OVERLAY_ALPHA
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

    /** Enable the handles for [ms] then auto-disable — safe way to test without
     *  leaving it on. Persists disabled so it never auto-activates after a crash. */
    fun testActivate(ms: Long) {
        OneHandPrefs.setEnabled(this, true)
        showHandles()
        handler.postDelayed({
            OneHandPrefs.setEnabled(this, false)
            hideHandles()
            Log.i(TAG, "testActivate: auto-disabled after ${ms}ms")
        }, ms)
        Log.i(TAG, "testActivate: on for ${ms}ms")
    }

    fun hideHandles() {
        views.forEach { runCatching { windowManager?.removeView(it) } }
        views.clear()
        handleRects.clear()
        cancelHandlePending(); activeHandle = null; activated = false
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Window opacity < 0.8 so Android 12+ "block untrusted touches" does NOT
        // drop taps that pass through this full-screen overlay to the app below
        // (this is what was silently killing Bitwarden's touches).
        lp.alpha = OVERLAY_ALPHA
        wm.addView(v, lp)
        preview = v
    }

    private fun addHandle(h: OneHandConfig.Handle) {
        val wm = windowManager ?: return
        val dm = resources.displayMetrics
        val view = View(this).apply {
            background = handleBackground(h.transparency)
            // Observe-only (API 34+): the window is non-touchable, so DON'T attach a
            // touch listener — detection happens in onMotionEvent, nothing is consumed.
            if (!observeMode) setOnTouchListener { v, e -> onHandleTouch(h, v, e) }
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS // reach the physical edge, not the safe area
        if (observeMode) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
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
        lp.alpha = OVERLAY_ALPHA // < 0.8 → doesn't block the app's touches (untrusted-touch rule)
        wm.addView(view, lp)
        views.add(view)
        Log.i(TAG, "addHandle ${h.id} edge=${h.edge} size=${lp.width}x${lp.height} x=${lp.x} y=${lp.y} inset=$inset")
        if (observeMode) {
            val rect = when (h.edge) {
                OneHandConfig.Edge.BOTTOM ->
                    android.graphics.Rect(lp.x, dm.heightPixels - inset - lp.height, lp.x + lp.width, dm.heightPixels - inset)
                OneHandConfig.Edge.LEFT ->
                    android.graphics.Rect(inset, lp.y, inset + lp.width, lp.y + lp.height)
                else ->
                    android.graphics.Rect(dm.widthPixels - inset - lp.width, lp.y, dm.widthPixels - inset, lp.y + lp.height)
            }
            handleRects.add(h to rect)
            Log.i(TAG, "handleRect ${h.id} = $rect (screen ${dm.widthPixels}x${dm.heightPixels})")
        }
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
        if (replaying) return false // our own injected tap — let it fall through
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
                } else {
                    // Short tap that did NOT open our menu → forward it to the app
                    // underneath (the handle window would otherwise swallow it).
                    val dwell = e.eventTime - e.downTime
                    val moved = hypot(e.rawX - downX, e.rawY - downY)
                    if (dwell < c.longPressMs && moved < dp(16)) {
                        Log.i(TAG, "UP ${h.id} short tap → replay to app @${downX.toInt()},${downY.toInt()}")
                        replayTap(view, downX, downY)
                    } else Log.i(TAG, "UP ${h.id} (not activated — nothing fired)")
                }
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

    /** Forward a short tap to the app underneath: make our handle non-touchable,
     *  inject a tap at the same point (goes to the app, not us), then restore. */
    private fun replayTap(view: View, x: Float, y: Float) {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        replaying = true
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager?.updateViewLayout(view, lp) }
        var restored = false
        val restore = {
            if (!restored) {
                restored = true; replaying = false
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { windowManager?.updateViewLayout(view, lp) }
            }
        }
        // Dispatch AFTER the FLAG_NOT_TOUCHABLE change lands (next frame), so the
        // injected tap reaches the app under the handle — not the handle itself.
        view.post {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            val ok = runCatching {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { restore() }
                    override fun onCancelled(d: GestureDescription?) { restore() }
                }, null)
            }.getOrDefault(false)
            if (!ok) { Log.i(TAG, "replayTap: dispatchGesture failed"); restore() }
        }
        handler.postDelayed({ restore() }, 500) // safety net so we never stay non-touchable
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
            if (OneHandPrefs.debugVisible(this@OneHandAccessibilityService)) {
                // Debug: bright bar + outline so placement/size is obvious on-device.
                setColor(Color.argb(120, 255, 0, 0))
                setStroke(dp(2), Color.argb(230, 255, 60, 60))
            } else {
                setColor(Color.argb((transparency.coerceIn(0, 100)) * 255 / 100, 77, 163, 255))
            }
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "OneHand"
        // Keep our overlay windows under the 0.8 opacity threshold of Android 12+
        // "block untrusted touches", so taps still reach the app underneath.
        private const val OVERLAY_ALPHA = 0.6f
        @Volatile var instance: OneHandAccessibilityService? = null
            private set
        val isConnected: Boolean get() = instance != null
    }
}
