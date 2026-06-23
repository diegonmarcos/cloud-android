package com.diegonmarcos.superapp.firewall

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Lifecycle facade for the firewall engine. The UI talks ONLY to this —
 * it never touches [FirewallVpnService] directly.
 */
object FirewallController {

    /** True when the user has the firewall switched on (desired state). */
    fun isEnabled(ctx: Context): Boolean = FirewallPrefs.isEnabled(ctx)

    /**
     * Ask the OS for VPN consent. Returns the Intent the caller must hand
     * to startActivityForResult, or null when consent was already granted
     * (caller may [start] directly).
     */
    fun consentIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    /** Start (or restart, to pick up block-list changes) the engine. Call
     *  only after VPN consent is granted, from a foreground context. */
    fun start(ctx: Context) {
        FirewallPrefs.setEnabled(ctx, true)
        ctx.startService(Intent(ctx, FirewallVpnService::class.java))
    }

    /** Stop the engine and clear the desired-on flag. */
    fun stop(ctx: Context) {
        FirewallPrefs.setEnabled(ctx, false)
        ctx.startService(
            Intent(ctx, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP)
        )
    }

    /** Re-apply the engine if it is enabled (after a per-app block toggle). */
    fun refresh(ctx: Context) {
        if (isEnabled(ctx)) start(ctx)
    }
}
