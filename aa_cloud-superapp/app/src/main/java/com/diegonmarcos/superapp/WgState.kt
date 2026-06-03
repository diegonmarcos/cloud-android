package com.diegonmarcos.superapp

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

/**
 * Process-wide WireGuard state: a single [GoBackend] + a single
 * [Tunnel] instance, lazily initialised from application context.
 *
 * Why a singleton: [GoBackend] tracks the active tunnel by reference
 * (`currentTunnel == tunnel` check inside `setStateInternal`). If
 * [WireGuardFragment] brings the tunnel up with `Tunnel #A` and
 * [com.diegonmarcos.superapp.devcontrol.DevControlFragment] later
 * queries `getStatistics(Tunnel #B)` with a freshly instantiated
 * sibling, GoBackend won't recognise it and returns empty stats. The
 * shared instance avoids that footgun.
 *
 * Name is pulled fresh from [WireGuardPrefs] every call (so renaming
 * the tunnel via the form is reflected immediately) — only the
 * Tunnel *object identity* is fixed.
 */
object WgState {
    @Volatile private var backendRef: GoBackend? = null
    @Volatile private var prefsRef: WireGuardPrefs? = null

    /** The single Tunnel instance — name resolved on demand. */
    val tunnel: Tunnel = object : Tunnel {
        override fun getName(): String = prefsRef?.tunnelName?.takeIf { it.isNotBlank() } ?: "wg-mesh"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    fun backend(ctx: Context): GoBackend {
        val app = ctx.applicationContext
        var b = backendRef
        if (b == null) {
            synchronized(this) {
                b = backendRef
                if (b == null) {
                    b = GoBackend(app)
                    backendRef = b
                }
            }
        }
        return b!!
    }

    fun prefs(ctx: Context): WireGuardPrefs {
        val app = ctx.applicationContext
        var p = prefsRef
        if (p == null) {
            synchronized(this) {
                p = prefsRef
                if (p == null) {
                    p = WireGuardPrefs(app)
                    prefsRef = p
                }
            }
        }
        return p!!
    }
}
