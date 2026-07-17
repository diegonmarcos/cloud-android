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
import com.diegonmarcos.superapp.onehand.CircularMenu

/**
 * Home-screen Canopus star (the 2nd-brightest, under the cube, a touch bigger
 * than Sirius). Glyph/size are data-driven from build.json::onehand.circular_menu
 * (via libs:onehand CircularMenu.config). Tapping it opens the libs:onehand
 * "circular-menu" — a two-level radial pie — centred on the star. Shown only on
 * the configured section (default `home`), mirroring SiriusStar.
 *
 * This class is the app-side bridge: it supplies the CircularMenu.Host so the
 * app-agnostic lib can navigate (onTileClicked), resolve icons (iconResFor), and
 * fan out a section's live pages (SectionPages) without the lib touching R/app.
 */
class CanopusStar(private val activity: AppCompatActivity) {

    private val cfg get() = CircularMenu.config()
    private var session: CircularMenu.Session? = null

    private val host = object : CircularMenu.Host {
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

        override fun pagesFor(section: String): List<CircularMenu.Leaf> =
            SectionPages.pagesFor(section).map {
                CircularMenu.Leaf(it.label, "", "page:$section/${it.id}")
            }
    }

    /** Wire glyph/size/position/tap once; call [update] after for initial state. */
    fun setup() {
        val star = activity.findViewById<TextView?>(R.id.canopus_star) ?: return
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat())
        // Bigger tap target (data-driven padding) so the star is easy to hit.
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Anchor near the BOTTOM (data-driven) so the half-moon menu opens upward.
        star.post { star.translationY = star.rootView.height * c.starBottomPct }
        // Forward the whole press→drag→release gesture to the menu so it's one
        // continuous motion (the overlay never sees the gesture that began here).
        star.setOnTouchListener { _, e ->
            val decor = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: return@setOnTouchListener false
            val s = IntArray(2); star.getLocationInWindow(s)
            val d = IntArray(2); decor.getLocationInWindow(d)
            val x = s[0] - d[0] + e.x
            val y = s[1] - d[1] + e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    session = CircularMenu.open(decor, x, y, host)
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

    /** Show only on the configured section (default `home`). */
    fun update(currentSection: String) {
        val star = activity.findViewById<TextView?>(R.id.canopus_star) ?: return
        val c = cfg
        star.visibility = if (c.enabled && currentSection == c.showOnSection) View.VISIBLE else View.GONE
    }
}
