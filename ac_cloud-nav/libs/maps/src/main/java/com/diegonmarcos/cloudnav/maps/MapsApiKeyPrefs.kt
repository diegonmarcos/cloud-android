package com.diegonmarcos.cloudnav.maps

import android.content.Context

/**
 * Per-provider API keys stored locally in SharedPreferences. Each key
 * lives at `key_<provider_id>` — the picker UI surfaces an inline
 * editor for any provider with `requires_api_key=true` in
 * build.json::ui.maps_providers.
 *
 * SECURITY NOTE: SharedPreferences is plaintext-on-disk inside the
 * app's private data directory. Other apps can't read it (per Android
 * sandbox), but a rooted device + ADB pull can. If you need stronger
 * protection later, swap to EncryptedSharedPreferences (Jetpack
 * Security) — same API, encrypted via Android Keystore.
 */
class MapsApiKeyPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_api_keys", Context.MODE_PRIVATE)

    fun get(providerId: String): String =
        sp.getString("key_$providerId", "").orEmpty()

    fun set(providerId: String, key: String) {
        sp.edit().putString("key_$providerId", key.trim()).apply()
    }

    fun hasKey(providerId: String): Boolean = get(providerId).isNotEmpty()
}
