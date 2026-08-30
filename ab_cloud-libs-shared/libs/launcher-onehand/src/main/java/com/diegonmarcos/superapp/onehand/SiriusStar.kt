package com.diegonmarcos.superapp.onehand

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Home-screen Sirius star — triggers [CircularMenu] on touch.
 *
 * Lives in libs:launcher-onehand along with Canopus and Centauri — ALL THREE home
 * stars are lib-side. Sirius's CONTENT is app-specific (the section/page
 * tree), so unlike Centauri it cannot be fully self-contained: the app
 * supplies a [CircularMenu.Host] (built from its own Sections/CircularMenuTree)
 * and a [twinkleEnabled] callback (the app's launcher-settings toggle) at
 * construction time. Everything else — glyph, size, touch-forwarding, the
 * twinkle animation, visibility gating — is generic and lives here.
 *
 * Same size source as Canopus/Centauri: both read
 * build.json::onehand.circular_menu.star.size_sp.
 */
class SiriusStar(
    private val activity: Activity,
    private val star: TextView,
    private val host: CircularMenu.Host,
    private val twinkleEnabled: () -> Boolean,
) {

    private var pulse: AnimatorSet? = null
    private var session: CircularMenu.Session? = null
    private val cfg get() = CircularMenu.config()

    /** Wire glyph/size/tap once. Call [update] afterwards for the initial state. */
    fun setup() {
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        // Sirius is slightly bigger than Canopus (which uses starSizeSp directly)
        star.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat() + 4f)
        val pad = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Golden glow to distinguish Sirius from Canopus/Centauri
        star.setShadowLayer(18f, 0f, 0f, android.graphics.Color.argb(200, 255, 220, 80))
        // Forward press→drag→release as one continuous gesture to the circular-menu.
        star.setOnTouchListener { _, e ->
            val decor = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: return@setOnTouchListener false
            val s = IntArray(2); star.getLocationInWindow(s)
            val d = IntArray(2); decor.getLocationInWindow(d)
            val x = s[0] - d[0] + e.x
            val y = s[1] - d[1] + e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Anchor at the star's CENTRE, not the touched pixel. The
                    // glyph carries ~22dp of tap padding on every side, so
                    // opening at e.x/e.y moved the whole menu — and its
                    // screen-edge clamp — by up to a finger-width, and the ring
                    // came out arranged differently on every single press.
                    val ax = s[0] - d[0] + star.width / 2f
                    val ay = s[1] - d[1] + star.height / 2f
                    session = CircularMenu.open(decor, ax, ay, host)
                    session?.feed(x, y, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_MOVE -> session?.feed(x, y, MotionEvent.ACTION_MOVE)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    session?.feed(x, y, e.actionMasked); session = null
                    star.performClick()
                }
            }
            true
        }
    }

    /** Show + twinkle the star only on the configured section. */
    fun update(currentSection: String) {
        val c = cfg
        if (c.enabled && currentSection == c.showOnSection) {
            star.visibility = View.VISIBLE
            val twinkle = twinkleEnabled()
            if (twinkle && pulse == null) {
                val sx = ObjectAnimator.ofFloat(star, "scaleX", 1f, 1.05f)
                val sy = ObjectAnimator.ofFloat(star, "scaleY", 1f, 1.05f)
                val al = ObjectAnimator.ofFloat(star, "alpha", 0.7f, 1f)
                for (a in listOf(sx, sy, al)) {
                    a.duration = 1800
                    a.repeatCount = ObjectAnimator.INFINITE
                    a.repeatMode = ObjectAnimator.REVERSE
                }
                pulse = AnimatorSet().apply { playTogether(sx, sy, al); start() }
            } else if (!twinkle) {
                pulse?.cancel(); pulse = null
                star.scaleX = 1f; star.scaleY = 1f; star.alpha = 1f
            }
        } else {
            star.visibility = View.GONE
            pulse?.cancel(); pulse = null
            star.scaleX = 1f; star.scaleY = 1f; star.alpha = 1f
        }
    }
}
