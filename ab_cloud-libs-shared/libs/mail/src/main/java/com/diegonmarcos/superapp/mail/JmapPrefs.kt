package com.diegonmarcos.superapp.mail

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted persistence of the JMAP login: server URL, email, password.
 * AndroidX security-crypto wraps SharedPreferences with AES-256 GCM at rest
 * (key in Android Keystore — hardware-backed on most modern phones).
 *
 * Stored fields are intentionally minimal: the JMAP discovery endpoint
 * regenerates the session JSON on every connect, so we don't cache it.
 */
class JmapPrefs(context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mail_jmap_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var server:   String get() = prefs.getString(K_SERVER, BuildConfig.JMAP_DEFAULT_SERVER) ?: ""
                         set(v) { prefs.edit().putString(K_SERVER, v).apply() }

    var email:    String get() = prefs.getString(K_EMAIL,    "") ?: ""
                         set(v) { prefs.edit().putString(K_EMAIL,    v).apply() }

    var password: String get() = prefs.getString(K_PASSWORD, "") ?: ""
                         set(v) { prefs.edit().putString(K_PASSWORD, v).apply() }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        private const val K_SERVER   = "server"
        private const val K_EMAIL    = "email"
        private const val K_PASSWORD = "password"
    }
}
