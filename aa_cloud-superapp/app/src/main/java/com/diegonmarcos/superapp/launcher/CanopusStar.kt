package com.diegonmarcos.superapp.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.onehand.ArcMenu
import com.diegonmarcos.superapp.onehand.CircularMenu

/**
 * Home-screen Canopus star — triggers the libs:onehand arc-menu on touch.
 *
 * The arc-menu opens at the bottom of the screen showing the children of the
 * section in build.json::onehand.arc_menu.section (default "config").
 *
 * Size/glyph/position read from build.json::onehand.circular_menu.star — same
 * config block as Sirius so both stars are always the same size.
 *
 * Distinct from Sirius star which opens the full multi-level circular-menu.
 */
class CanopusStar(private val activity: AppCompatActivity) {

    private val cfg get() = CircularMenu.config()   // size/glyph same as Sirius
    private var session: ArcMenu.Session? = null

    private val host = object : ArcMenu.Host {
        override fun navigate(target: String) {
            (activity as? MainActivity)?.onTileClicked(target)
        }

        override fun iconBitmap(name: String, sizePx: Int): Bitmap? {
            if (name.isBlank() || sizePx <= 0) return null
            val resId = Sections.iconResFor(activity, name)
            if (resId == 0) return null
            val d = ContextCompat.getDrawable(activity, resId) ?: return null
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, sizePx, sizePx); d.draw(Canvas(bmp))
            return bmp
        }

        override fun itemsFor(section: String): List<ArcMenu.Item> =
            SectionPages.pagesFor(section).map {
                ArcMenu.Item(it.label, "", "page:$section/${it.id}")
            }
    }

    /** Wire glyph/size/position/tap once; call [update] after for initial state. */
    fun setup() {
        val star = activity.findViewById<TextView?>(R.id.canopus_star) ?: return
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat())
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Anchor just above the bottom nav island so the arc opens upward.
        star.post {
            val island = activity.findViewById<View?>(R.id.bottom_nav_island)
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
        val star = activity.findViewById<TextView?>(R.id.canopus_star) ?: return
        val c = cfg
        star.visibility = if (c.enabled && currentSection == c.showOnSection) View.VISIBLE else View.GONE
    }
}
