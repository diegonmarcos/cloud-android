package com.diegonmarcos.superapp.system
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.Sections

import android.content.Context
import android.content.SharedPreferences

/**
 * Apps/Admin global UI toggle persistence. Plain SharedPreferences — this
 * is a view-pref, not a secret. Default comes from
 * BuildConfig.UI_DEFAULT_MODE (build.json::ui.default_mode).
 */
class ModePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("mode_prefs", Context.MODE_PRIVATE)

    var mode: String
        get() = sp.getString(K_MODE, Sections.defaultMode()) ?: Sections.defaultMode()
        set(value) { sp.edit().putString(K_MODE, value).apply() }

    fun toggle(): String {
        val next = if (mode == "admin") "apps" else "admin"
        mode = next
        return next
    }

    companion object { private const val K_MODE = "mode" }
}
