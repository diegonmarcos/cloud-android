package com.diegonmarcos.superapp

import android.content.Context
import android.content.SharedPreferences

/**
 * User profile persistence — name + email + initials. Plain
 * SharedPreferences (display data, not a secret). Defaults seed from
 * `build.json::ui.profile_default` baked into BuildConfig at build time.
 *
 * Consumers:
 *  • Drawer header — shows "{initials} | {mode}".
 *  • Configs → Profile page — read/write form bound to these fields.
 */
class ProfilePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString(K_NAME, BuildConfig.UI_PROFILE_NAME) ?: BuildConfig.UI_PROFILE_NAME
        set(value) { sp.edit().putString(K_NAME, value).apply() }

    var email: String
        get() = sp.getString(K_EMAIL, BuildConfig.UI_PROFILE_EMAIL) ?: BuildConfig.UI_PROFILE_EMAIL
        set(value) { sp.edit().putString(K_EMAIL, value).apply() }

    var initials: String
        get() = sp.getString(K_INITIALS, BuildConfig.UI_PROFILE_INITIALS) ?: BuildConfig.UI_PROFILE_INITIALS
        set(value) { sp.edit().putString(K_INITIALS, value).apply() }

    companion object {
        private const val K_NAME     = "name"
        private const val K_EMAIL    = "email"
        private const val K_INITIALS = "initials"
    }
}
