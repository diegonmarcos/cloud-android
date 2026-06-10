package com.diegonmarcos.superapp.ops.dagu

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Dagu credential storage. Backed by [EncryptedSharedPreferences]
 * (AES-256-GCM / AES-256-SIV). Falls back to plain SharedPreferences
 * when EncryptedSharedPreferences can't initialise (corrupted
 * KeyStore on a sketchy OEM) — degraded > crashed.
 *
 * Three persistent fields:
 *   • serverUrl    — pre-filled from BuildConfig.DAGU_DEFAULT_SERVER
 *                    on first launch, then user-overridable.
 *   • bearerToken  — Authelia OAuth bearer token; sent as
 *                    `Authorization: Bearer ...` on every Dagu
 *                    REST call. The user gets the token from
 *                    `~/git/vault/A0_keys/providers/authelia/oauth/
 *                    get_token.py` and pastes it into the login
 *                    form once.
 *   • userLabel    — purely cosmetic; "logged in as N" line on the
 *                    main view. Not used for any auth.
 */
class DaguPrefs(context: Context) {

    private val sp: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "dagu_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Throwable) {
        context.applicationContext.getSharedPreferences(
            "dagu_prefs_fallback", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = sp.getString("server_url", BuildConfig.DAGU_DEFAULT_SERVER)
            ?: BuildConfig.DAGU_DEFAULT_SERVER
        set(value) { sp.edit().putString("server_url", value).apply() }

    var bearerToken: String
        get() = sp.getString("bearer_token", "") ?: ""
        set(value) { sp.edit().putString("bearer_token", value).apply() }

    var userLabel: String
        get() = sp.getString("user_label", "") ?: ""
        set(value) { sp.edit().putString("user_label", value).apply() }

    fun isLoggedIn(): Boolean = bearerToken.isNotBlank()

    fun clear() { sp.edit().clear().apply() }
}
