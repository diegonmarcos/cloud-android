package com.diegonmarcos.superapp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted user-imported configs (WG private keys, mail URLs, SSH key,
 * authelia token …). Stored in EncryptedSharedPreferences — AES-256-GCM
 * at rest, key in Android Keystore (hardware-backed on most phones).
 *
 * One blob (`configs_json`) holds the full pasted JSON. Per-field
 * consumers (JmapPrefs, the future libs:net WG module, ssh-agent for
 * vault clone) parse what they need lazily; this class doesn't impose a
 * schema beyond "valid JSON".
 */
class ConfigsPrefs(context: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "import_configs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var json: String
        get() = prefs.getString(K_JSON, "") ?: ""
        set(v) { prefs.edit().putString(K_JSON, v).apply() }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        private const val K_JSON = "configs_json"
    }
}
