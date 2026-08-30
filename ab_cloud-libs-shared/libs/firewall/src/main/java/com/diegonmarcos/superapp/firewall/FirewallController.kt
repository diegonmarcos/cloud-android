package com.diegonmarcos.superapp.firewall

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Lifecycle facade for the firewall engine. The UI talks ONLY to this.
 *
 * Drives the shipping interim [FirewallVpnService]. "enabled" is the user's
 * DESIRED state (persisted); the service is started only when enabled AND at
 * least one app has a policy — so flipping the master switch on with no rules
 * yet is a valid idle-on state that doesn't snap back off.
 *
 * The merged firestack engine ([FirestackTunnelService]) is compiled and
 * manifest-declared but NOT started here yet: Android allows one active VPN,
 * so switching engines is a deliberate step that needs on-device validation.
 */
object FirewallController {

    /**
     * The app's cloud-VPN seam, injected once at startup.
     *
     * [FirestackTunnelService] routes "cloud-VPN only" flows through the
     * WireGuard proxy, whose config lives in libs:net / the app's network
     * package. libs:firewall must not import those (lib->app is forbidden), so
     * the app sets this instead and the firewall depends only on the interface.
     *
     * Defaults to [CloudVpnProvider.NONE], under which every vpn-only app is
     * simply blocked — the safe reading when no tunnel is configured, and the
     * reason this is non-null rather than lateinit: a firewall that throws on
     * an unset provider fails open.
     */
    @Volatile
    @JvmStatic
    var cloudVpn: CloudVpnProvider = CloudVpnProvider.NONE

    fun isEnabled(ctx: Context): Boolean = FirewallPrefs.isEnabled(ctx)

    /** VPN consent Intent for startActivityForResult, or null if already granted. */
    fun consentIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    /** Turn the firewall on (desired state) and reconcile the service. Call
     *  only after VPN consent is granted, from a foreground context. */
    fun start(ctx: Context) {
        FirewallPrefs.setEnabled(ctx, true)
        reconcile(ctx)
    }

    /** Stop the engine and clear the desired-on flag. */
    fun stop(ctx: Context) {
        FirewallPrefs.setEnabled(ctx, false)
        ctx.startService(
            Intent(ctx, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP)
        )
    }

    /** Re-apply after a rule change (the running service recomputes its block
     *  set). No-op when off. */
    fun refresh(ctx: Context) {
        if (isEnabled(ctx)) reconcile(ctx)
    }

    /** When enabled, the VPN is ALWAYS established (even with no configured
     *  apps) so the toggle visibly turns on; per-app rules then take effect
     *  live. Disabling stops it. */
    private fun reconcile(ctx: Context) {
        ctx.startService(Intent(ctx, FirewallVpnService::class.java))
    }
}
