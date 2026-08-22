package com.diegonmarcos.superapp.settings

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.BuildConfig

/**
 * Runtime overrides for the three home-screen swipe actions (up / left /
 * right), editable live in Configs › One-Hand. Defaults come from
 * build.json::onehand.home_swipes (baked into BuildConfig.HOME_SWIPE_*), so a
 * fresh install behaves exactly as the data-driven config declares until the
 * user changes it. A plain view-pref, not a secret.
 *
 * These fire ONLY while on the Home section — MainActivity's nav-swipe handler
 * guards on currentSection == "home"; off-home the inner fragment owns the
 * gesture. Action ids are the same vocabulary MainActivity.handleHomeSwipeAction
 * understands (see [ACTIONS]).
 */
class HomeSwipePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("home_swipe_prefs", Context.MODE_PRIVATE)

    var up: String
        get() = sp.getString(K_UP, BuildConfig.HOME_SWIPE_UP) ?: BuildConfig.HOME_SWIPE_UP
        set(value) { sp.edit().putString(K_UP, value).apply() }

    var left: String
        get() = sp.getString(K_LEFT, BuildConfig.HOME_SWIPE_LEFT) ?: BuildConfig.HOME_SWIPE_LEFT
        set(value) { sp.edit().putString(K_LEFT, value).apply() }

    var right: String
        get() = sp.getString(K_RIGHT, BuildConfig.HOME_SWIPE_RIGHT) ?: BuildConfig.HOME_SWIPE_RIGHT
        set(value) { sp.edit().putString(K_RIGHT, value).apply() }

    var down: String
        get() = sp.getString(K_DOWN, BuildConfig.HOME_SWIPE_DOWN) ?: BuildConfig.HOME_SWIPE_DOWN
        set(value) { sp.edit().putString(K_DOWN, value).apply() }

    companion object {
        private const val K_UP = "swipe_up"
        private const val K_LEFT = "swipe_left"
        private const val K_RIGHT = "swipe_right"
        private const val K_DOWN = "swipe_down"

        /** UI-facing action vocabulary: id → human label. Order = picker order.
         *  Kept in sync with MainActivity.handleHomeSwipeAction. */
        val ACTIONS: List<Pair<String, String>> = listOf(
            "open_home_apps"          to "Open Home Apps",
            "open_last_superapp_page" to "Last SuperApp page",
            "open_last_android_app"   to "Last Android app",
            "walk_step_next"          to "Walk → next",
            "tab:phone:suite"         to "Suite: Phone Quickmarks",
            "walk_step_prev"          to "Walk ← prev",
        )
    }
}
