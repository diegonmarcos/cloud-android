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

    var company: String
        get() = sp.getString(K_COMPANY, BuildConfig.UI_PROFILE_COMPANY) ?: BuildConfig.UI_PROFILE_COMPANY
        set(value) { sp.edit().putString(K_COMPANY, value).apply() }

    var location: String
        get() = sp.getString(K_LOCATION, BuildConfig.UI_PROFILE_LOCATION) ?: BuildConfig.UI_PROFILE_LOCATION
        set(value) { sp.edit().putString(K_LOCATION, value).apply() }

    var website: String
        get() = sp.getString(K_WEBSITE, BuildConfig.UI_PROFILE_WEBSITE) ?: BuildConfig.UI_PROFILE_WEBSITE
        set(value) { sp.edit().putString(K_WEBSITE, value).apply() }

    var titles: String
        get() = sp.getString(K_TITLES, BuildConfig.UI_PROFILE_TITLES) ?: BuildConfig.UI_PROFILE_TITLES
        set(value) { sp.edit().putString(K_TITLES, value).apply() }

    /** Picked from the device gallery via Configs → Profile. Either an
     *  `android.net.Uri` toString() or empty if the user hasn't picked
     *  one yet. BusinessCardFragment falls back to a generated avatar
     *  with initials when this is empty. */
    var pictureUri: String
        get() = sp.getString(K_PICTURE, "") ?: ""
        set(value) { sp.edit().putString(K_PICTURE, value).apply() }

    var bannerUri: String
        get() = sp.getString(K_BANNER, "") ?: ""
        set(value) { sp.edit().putString(K_BANNER, value).apply() }

    companion object {
        private const val K_NAME     = "name"
        private const val K_EMAIL    = "email"
        private const val K_INITIALS = "initials"
        private const val K_COMPANY  = "company"
        private const val K_LOCATION = "location"
        private const val K_WEBSITE  = "website"
        private const val K_TITLES   = "titles"
        private const val K_PICTURE  = "picture_uri"
        private const val K_BANNER   = "banner_uri"
    }
}
