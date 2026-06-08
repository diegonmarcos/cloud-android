package com.diegonmarcos.superapp

import android.content.Context

/**
 * Persistent backing for the user's currently-selected launcher theme.
 * Read by MainActivity (status-bar trim + home-pane swap) and by
 * LauncherConfigFragment (the picker UI).
 *
 * Theme IDs match build.json::ui.launcher_themes[*].id and the
 * [LauncherTheme] enum below — same string flows through the picker
 * tile id, prefs storage, and the runtime switch in MainActivity.
 */
class LauncherThemePrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var theme: LauncherTheme
        get() = LauncherTheme.fromId(sp.getString(KEY_THEME, null))
        set(value) {
            sp.edit().putString(KEY_THEME, value.id).apply()
        }

    companion object {
        private const val PREFS     = "launcher_theme_prefs"
        private const val KEY_THEME = "theme"
    }
}

/** Enum mirror of build.json::ui.launcher_themes. New themes land
 *  here AND in build.json — keeping the picker data-driven from JSON
 *  while keeping the runtime switch a closed enum. */
enum class LauncherTheme(val id: String) {
    Cloud("cloud"),
    CloudMinimalistBlack("cloud_minimalist_black"),
    CloudPowerSaving("cloud_power_saving");

    companion object {
        fun fromId(id: String?): LauncherTheme =
            values().firstOrNull { it.id == id } ?: Cloud
    }
}
