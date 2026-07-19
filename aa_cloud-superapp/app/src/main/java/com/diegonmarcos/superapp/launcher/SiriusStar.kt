package com.diegonmarcos.superapp.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
 * Home-screen Sirius star — triggers the libs:onehand circular-menu on touch.
 *
 * Same size as Canopus: both read build.json::onehand.circular_menu.star.size_sp.
 * Shown only on the `home` section (same show_on_section as Canopus).
 *
 * Distinct from Canopus star which opens the bottom arc-menu (Configs children).
 */
class SiriusStar(private val activity: AppCompatActivity) {

    private var pulse: AnimatorSet? = null
    private var session: CircularMenu.Session? = null
    private val cfg get() = CircularMenu.config()

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

    /** Wire glyph/size/tap once. Call [update] afterwards for the initial state. */
    fun setup() {
        val star = activity.findViewById<TextView?>(R.id.sirius_star) ?: return
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        // Sirius is slightly bigger than Canopus (which uses starSizeSp directly)
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat() + 4f)
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Golden glow to distinguish Sirius from Canopus
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

    /** Show + twinkle the star only on the configured section. */
    fun update(currentSection: String) {
        val star = activity.findViewById<TextView?>(R.id.sirius_star) ?: return
        val c = cfg
        if (c.enabled && currentSection == c.showOnSection) {
            star.visibility = View.VISIBLE
            val twinkle = com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(activity).toggle("star_twinkle")
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
