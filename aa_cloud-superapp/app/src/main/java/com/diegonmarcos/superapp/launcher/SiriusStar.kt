package com.diegonmarcos.superapp.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.floatingnav.FloatingNavService

/**
 * Home-screen Sirius star, extracted from MainActivity. Glyph + size are
 * data-driven (build.json::ui.sirius_star → BuildConfig.SIRIUS_STAR_*). The star
 * is shown + gently twinkling only on the `home` section; tapping it force-opens
 * the FloatingNav menu (toast if the overlay permission is absent).
 */
class SiriusStar(private val activity: AppCompatActivity) {

    private var pulse: AnimatorSet? = null

    /** Wire glyph/size/tap once. Call [update] afterwards for the initial state. */
    fun setup() {
        val star = activity.findViewById<TextView?>(R.id.sirius_star) ?: return
        if (!BuildConfig.SIRIUS_STAR_ENABLED) { star.visibility = View.GONE; return }
        star.text = BuildConfig.SIRIUS_STAR_GLYPH
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, BuildConfig.SIRIUS_STAR_SIZE_SP)
        star.setOnClickListener {
            if (!FloatingNavService.showMenu(activity)) {
                Toast.makeText(activity,
                    "Grant 'Display over other apps' to open the nav menu", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Show + twinkle the star only on the `home` section. */
    fun update(currentSection: String) {
        val star = activity.findViewById<TextView?>(R.id.sirius_star) ?: return
        if (BuildConfig.SIRIUS_STAR_ENABLED && currentSection == "home") {
            star.visibility = View.VISIBLE
            // Twinkle only when the battery-hungry "Star twinkle" toggle is on
            // (Configs → Launcher → Battery Hunger Ones). Off = static star.
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
