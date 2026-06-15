package com.diegonmarcos.superapp.launcher

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.Trace
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Random

/**
 * Toolbar/bottom-nav "liveliness" effects, extracted from MainActivity so the
 * Activity isn't the home for ~250 lines of pure UI animation glue:
 *   • long-press fan on the Home bottom-nav item ([HomeFanMenu])
 *   • a tooltip-consumer that suppresses OEM long-press tooltips
 *   • a periodic low-amplitude "jitter" tic on the drawer hamburger
 *
 * Owns its own handlers/state (no getter/setter plumbing back into the Activity).
 * Lifecycle: [install] once from onCreate; [resume]/[pause] from the Activity.
 */
class LauncherToolbarFx(
    private val activity: AppCompatActivity,
    private val bottomNav: BottomNavigationView,
    private val onTile: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random()

    // ── fan-menu drag state (was passed around MainActivity as lambdas) ──
    private var pending: Runnable? = null
    private var fanCtrl: HomeFanMenu.Controller? = null
    private var downX = 0f
    private var downY = 0f

    private var jitterRunnable: Runnable? = null

    /** One-time setup from onCreate. */
    fun install() {
        installHomeLongPressFan()
        installTooltipConsumer()
    }

    fun resume() = scheduleHamburgerJitter()
    fun pause() = cancelHamburgerJitter()

    // ── long-press fan on the Home item ─────────────────────────────────
    private fun installHomeLongPressFan() {
        bottomNav.post {
            val homeView = findHomeNavView()
            if (homeView == null) { Trace.w(TAG, "fan install: no homeView — long-press disabled"); return@post }
            Trace.i(TAG, "fan install: attaching listener to home item view")
            attachHomeFanTouchListener(homeView)
        }
    }

    private fun attachHomeFanTouchListener(homeView: View) {
        homeView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    pending?.let { handler.removeCallbacks(it) }
                    fanCtrl = null
                    val p = Runnable {
                        homeView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        fanCtrl = HomeFanMenu.show(homeView) { target -> onTile(target) }
                    }
                    pending = p
                    handler.postDelayed(p, 380)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val ctrl = fanCtrl
                    if (ctrl != null) { ctrl.updateFinger(ev.rawX, ev.rawY); true }
                    else {
                        if (Math.abs(ev.x - downX) > 140 || Math.abs(ev.y - downY) > 140) {
                            pending?.let { handler.removeCallbacks(it) }; pending = null
                        }
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }; pending = null
                    val c = fanCtrl; fanCtrl = null
                    if (c != null) {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) c.commit() else c.dismiss()
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun findHomeNavView(): View? {
        val menuView = bottomNav.getChildAt(0) as? ViewGroup ?: return null
        val items = bottomNav.menu
        for (i in 0 until items.size()) {
            if (items.getItem(i).itemId == R.id.nav_home) return menuView.getChildAt(i)
        }
        return null
    }

    // ── tooltip consumer (OEM long-press tooltip suppression) ───────────
    private fun installTooltipConsumer() {
        val consumer = View.OnLongClickListener { Trace.i(TAG, "tooltip consumer fired id=${it.id}"); true }
        fun applyToChildren(vg: ViewGroup) {
            for (i in 0 until vg.childCount) vg.getChildAt(i).setOnLongClickListener(consumer)
        }
        val hookListener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { child?.setOnLongClickListener(consumer) }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        }
        bottomNav.post {
            (bottomNav.getChildAt(0) as? ViewGroup)?.let {
                it.setOnHierarchyChangeListener(hookListener); applyToChildren(it)
            }
        }
        val toolbar = activity.findViewById<View>(R.id.toolbar) as? ViewGroup ?: return
        toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.setOnLongClickListener(consumer)
                if (child is ViewGroup) { child.setOnHierarchyChangeListener(hookListener); applyToChildren(child) }
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        applyToChildren(toolbar)
    }

    // ── hamburger jitter — random low-amplitude tic every 3–5s ──────────
    private fun scheduleHamburgerJitter() {
        cancelHamburgerJitter()
        val toolbar: MaterialToolbar = activity.findViewById(R.id.toolbar) ?: return
        toolbar.post { findToolbarNavIcon(toolbar)?.let { postNextJitter(it) } }
    }

    private fun cancelHamburgerJitter() {
        jitterRunnable?.let { handler.removeCallbacks(it) }
        jitterRunnable = null
    }

    private fun findToolbarNavIcon(toolbar: ViewGroup): View? {
        val navDrawable = (toolbar as? MaterialToolbar)?.navigationIcon
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (v is ImageButton && (navDrawable == null || v.drawable == navDrawable)) return v
        }
        return null
    }

    private fun postNextJitter(view: View) {
        val delayMs = 3000L + rng.nextInt(2000)
        jitterRunnable = Runnable { playHamburgerJitter(view); postNextJitter(view) }
        handler.postDelayed(jitterRunnable!!, delayMs)
    }

    private fun playHamburgerJitter(view: View) {
        val flavour = rng.nextInt(3)
        val durMs = 240L + rng.nextInt(160)
        val rotPeak = (rng.nextFloat() * 8f + 4f) * if (rng.nextBoolean()) 1f else -1f
        val xPeak = (rng.nextFloat() * 4f + 2f) * if (rng.nextBoolean()) 1f else -1f
        val animators = mutableListOf<android.animation.ObjectAnimator>()
        if (flavour != 1) animators += android.animation.ObjectAnimator.ofFloat(
            view, "translationX", 0f, xPeak, -xPeak * 0.6f, xPeak * 0.25f, 0f).apply { duration = durMs }
        if (flavour != 0) animators += android.animation.ObjectAnimator.ofFloat(
            view, "rotation", 0f, rotPeak, -rotPeak * 0.6f, rotPeak * 0.25f, 0f).apply { duration = durMs }
        android.animation.AnimatorSet().apply { playTogether(*animators.toTypedArray()); start() }
    }

    private companion object { const val TAG = "LauncherToolbarFx" }
}
