package com.diegonmarcos.superapp.onehand

import android.app.Activity
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Home-screen Canopus star — triggers [ArcMenu] (the bottom half-moon menu)
 * on touch.
 *
 * Lives in libs:onehand along with Sirius and Centauri — ALL THREE home
 * stars are lib-side. Canopus's CONTENT is app-specific (a build.json
 * section's pages), so unlike Centauri it cannot be fully self-contained:
 * the app supplies an [ArcMenu.Host] (built from its own SectionPages) and
 * the bottom-nav island [View] to anchor against, at construction time.
 * Everything else — glyph, size, touch-forwarding, position, visibility
 * gating — is generic and lives here.
 *
 * Size/glyph/position read from build.json::onehand.circular_menu.star —
 * same config block as Sirius so both stars are always the same size.
 */
class CanopusStar(
    private val activity: Activity,
    private val star: TextView,
    private val island: View?,
    private val host: ArcMenu.Host,
) {

    private val cfg get() = CircularMenu.config()   // size/glyph same as Sirius
    private var session: ArcMenu.Session? = null

    /** Wire glyph/size/position/tap once; call [update] after for initial state. */
    fun setup() {
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat())
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Anchor just above the bottom nav island so the arc opens upward.
        star.post {
            if (island != null && island.height > 0) {
                val s = IntArray(2); star.getLocationInWindow(s)
                val n = IntArray(2); island.getLocationInWindow(n)
                val gap = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics)
                star.translationY = (n[1] - gap - (s[1] + star.height)).toFloat()
            } else {
                star.translationY = star.rootView.height * c.starBottomPct
            }
        }
        // Forward press→drag→release to the arc-menu.
        star.setOnTouchListener { _, e ->
            val decor = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: return@setOnTouchListener false
            val s = IntArray(2); star.getLocationInWindow(s)
            val d = IntArray(2); decor.getLocationInWindow(d)
            val x = s[0] - d[0] + e.x
            val y = s[1] - d[1] + e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    session = ArcMenu.open(decor, x, y, host)
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

    /** Show only on the `home` section (same as circular-menu's show_on_section). */
    fun update(currentSection: String) {
        val c = cfg
        star.visibility = if (c.enabled && currentSection == c.showOnSection) View.VISIBLE else View.GONE
    }
}
