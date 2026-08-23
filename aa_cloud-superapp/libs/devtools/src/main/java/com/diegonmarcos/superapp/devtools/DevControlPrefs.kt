package com.diegonmarcos.superapp.devtools

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

    /** Master on/off for the loopback HTTP control surface. Default
     *  true preserves the legacy behaviour (always on). When false,
     *  [DevControlServer.start] is a no-op and Configs → About hides
     *  the curl shortcuts behind a "service stopped" caption. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(value) { sp.edit().putBoolean(K_ENABLED, value).apply() }

    fun resetToken(): String {
        val fresh = UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    /** True once a token exists on disk. Distinguishes "never adopted" from
     *  "adopted earlier", which [FleetToken] needs because reading [token]
     *  would silently mint a local one and split the fleet in two. */
    val hasToken: Boolean
        get() = !sp.getString(K_TOKEN, null).isNullOrBlank()

    /** Take the fleet token minted by the authority app. Writing it into the
     *  same slot [token] reads means the app-owned DevControlServer on 38080
     *  and [AppDebugServer] on 38090+ end up sharing one token, which is the
     *  point — one secret for the whole fleet, not one per app per port. */
    fun adopt(fleetToken: String) {
        if (fleetToken.isBlank()) return
        if (sp.getString(K_TOKEN, null) == fleetToken) return
        sp.edit().putString(K_TOKEN, fleetToken).apply()
    }

    companion object {
        const val DEFAULT_PORT = 38080
        private const val PREFS    = "dev_control"
        private const val K_PORT   = "port"
        private const val K_TOKEN  = "token"
        private const val K_ENABLED = "enabled"
    }
}
