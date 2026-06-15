package com.diegonmarcos.superapp.settings

import android.content.Context

/**
 * Persistent backing for the user's currently-selected launcher
 * profile. Read by Configs → Launcher (the picker UI) and, in a
 * follow-up patch, by the Phone tab to filter which folders / apps
 * a Work / Guest session sees.
 *
 * Profile IDs match build.json::ui.launcher_profiles[*].id and the
 * [LauncherProfile] enum below — same string flows through the
 * picker tile id, prefs storage, and any runtime filter.
 */
class LauncherProfilePrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var profile: LauncherProfile
        get() = LauncherProfile.fromId(sp.getString(KEY_PROFILE, null))
        set(value) {
            sp.edit().putString(KEY_PROFILE, value.id).apply()
        }

    companion object {
        private const val PREFS       = "launcher_profile_prefs"
        private const val KEY_PROFILE = "profile"
    }
}

/** Enum mirror of build.json::ui.launcher_profiles. */
enum class LauncherProfile(val id: String) {
    Personal("personal"),
    Work("work"),
    Guest("guest");

    companion object {
        fun fromId(id: String?): LauncherProfile =
            values().firstOrNull { it.id == id } ?: Personal
    }
}
