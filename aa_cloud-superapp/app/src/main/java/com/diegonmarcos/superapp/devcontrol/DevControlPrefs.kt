package com.diegonmarcos.superapp.devcontrol

import android.content.Context
import java.util.UUID

/**
 * Loopback HTTP control surface prefs — token + port.
 * Token is generated once on first launch, persisted in
 * SharedPreferences. NOT EncryptedSharedPreferences because the
 * server only binds to 127.0.0.1; anyone with shell access on the
 * device already trivially owns the user.
 */
class DevControlPrefs(context: Context) {
    private val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val port: Int
        get() = sp.getInt(K_PORT, DEFAULT_PORT)

    val token: String
        get() {
            sp.getString(K_TOKEN, null)?.let { if (it.isNotBlank()) return it }
            val fresh = UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(K_TOKEN, fresh).apply()
            return fresh
        }

    fun resetToken(): String {
        val fresh = UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    companion object {
        const val DEFAULT_PORT = 38080
        private const val PREFS   = "dev_control"
        private const val K_PORT  = "port"
        private const val K_TOKEN = "token"
    }
}
