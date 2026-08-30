package com.diegonmarcos.superapp.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Mattermost credential + session storage. Backed by
 * [EncryptedSharedPreferences] (AES-256-GCM + AES-256-SIV) — same
 * pattern libs/mail uses for JMAP creds. Falls back to plain
 * SharedPreferences when EncryptedSharedPreferences can't initialise
 * (e.g. corrupted KeyStore on a sketchy OEM) — better degraded
 * storage than crashing the section out.
 *
 * Three persistent values:
 *   • serverUrl  — pre-filled from BuildConfig.MATTERMOST_DEFAULT_SERVER
 *                  on first launch, then user-overridable.
 *   • token      — Mattermost session token returned by /users/login
 *                  (header `Token: ...`). Authenticates every
 *                  subsequent REST call.
 *   • userId     — id of the logged-in user; needed for the per-user
 *                  category + members endpoints.
 */
class MattermostPrefs(context: Context) {

    private val sp: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "mattermost_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Throwable) {
        // Fallback only — see KDoc above for why.
        context.applicationContext.getSharedPreferences(
            "mattermost_prefs_fallback", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = sp.getString("server_url", BuildConfig.MATTERMOST_DEFAULT_SERVER)
            ?: BuildConfig.MATTERMOST_DEFAULT_SERVER
        set(value) { sp.edit().putString("server_url", value).apply() }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) { sp.edit().putString("token", value).apply() }

    var userId: String
        get() = sp.getString("user_id", "") ?: ""
        set(value) { sp.edit().putString("user_id", value).apply() }

    fun isLoggedIn(): Boolean = token.isNotBlank() && userId.isNotBlank()

    /** Wipe everything — used by the "Sign out" action. */
    fun clear() {
        sp.edit().clear().apply()
    }
}
